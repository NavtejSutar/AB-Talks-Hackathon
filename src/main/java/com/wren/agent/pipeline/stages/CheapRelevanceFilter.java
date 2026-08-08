package com.wren.agent.pipeline.stages;

import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.persona.PersonaProfile;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cheap, zero-LLM pre-filter that runs BEFORE editorial scoring.
 *
 * <p>Goals:
 * <ol>
 *   <li>Eliminate candidates with no AI-security relevance signal (no keyword match AND not Tier A)
 *       before any expensive LLM call is made.</li>
 *   <li>Cap the candidate set to {@code wren.pipeline.max-candidates-for-llm} (default 10),
 *       prioritising Tier A sources and recency.</li>
 * </ol>
 *
 * <p>Rejected candidates are persisted with decision=REJECTED, stage=CHEAP_RELEVANCE_FILTER
 * so they are visible in the debug endpoints and never re-queued.
 */
@Component
public class CheapRelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(CheapRelevanceFilter.class);

    // High-value AI/LLM-specific security keywords earn extra weight in ranking.
    // Excludes bare 'cve' and 'zero-day' — those appear in ANY CVE regardless of AI relevance.
    private static final Set<String> HIGH_VALUE_KEYWORDS = Set.of(
            "prompt injection", "jailbreak", "adversarial", "llm", "ai security",
            "ai agent", "model weight", "supply chain", "red-team", "red team",
            "backdoor", "poisoning", "exploit"
    );

    /**
     * Keywords used for cheap RELEVANCE filtering (must-match at least one).
     * Intentionally more specific than {@link com.wren.agent.persona.PersonaProfile#STABLE_INTERESTS}:
     * excludes bare "CVE" and "incident" which match ANY security content regardless of AI relevance.
     */
    private static final List<String> FILTER_KEYWORDS = List.of(
            "prompt injection", "jailbreak", "adversarial example", "adversarial ml",
            "adversarial attack", "adversarial", "evasion", "ml classifier",
            "ai supply chain", "model weight", "dataset", "mcp server",
            "package registry", "supply chain", "ai agent security", "ml system",
            "exploit", "defense", "backdoor", "poisoning", "red-teaming", "red team",
            "machine learning", "neural network", "deep learning", "language model",
            "llm", "ai security", "tool use", "model security", "ai agent",
            "vulnerability in ai", "vulnerability in ml"
    );

    @Value("${wren.pipeline.max-candidates-for-llm:10}")
    private int maxCandidatesForLlm;

    private final TopicCandidateRepository topicCandidateRepository;

    public CheapRelevanceFilter(TopicCandidateRepository topicCandidateRepository) {
        this.topicCandidateRepository = topicCandidateRepository;
    }

    /**
     * Filters and ranks candidates without any LLM call.
     *
     * @param candidates credibility-checked candidates (Tier A/B only)
     * @param agentId    used for persisting rejections
     * @param tickId     used for persisting rejections
     * @return ordered, capped list ready for batch LLM scoring
     */
    @Transactional
    public List<NormalizedCandidate> filter(List<NormalizedCandidate> candidates,
                                            UUID agentId, UUID tickId) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<NormalizedCandidate> relevant = new ArrayList<>();
        List<NormalizedCandidate> irrelevant = new ArrayList<>();

        for (NormalizedCandidate c : candidates) {
            if (hasRelevanceSignal(c)) {
                relevant.add(c);
            } else {
                irrelevant.add(c);
            }
        }

        log.info("CheapRelevanceFilter: {}/{} candidates have relevance signal",
                relevant.size(), candidates.size());

        // Persist rejections
        for (NormalizedCandidate c : irrelevant) {
            log.info("CheapRelevanceFilter REJECTED (no security keywords): [{}] '{}'",
                    c.getSource(), c.getTitle());
            persistRejection(c, agentId, tickId,
                    "No AI-security keyword match in title/summary; source=" + c.getSource());
        }

        // Sort by ranking score descending (relevance + tier bonus + recency tiebreaker)
        relevant.sort(Comparator.comparingDouble(this::rankScore).reversed());

        // Cap at max
        List<NormalizedCandidate> capped;
        if (relevant.size() > maxCandidatesForLlm) {
            List<NormalizedCandidate> overflow = relevant.subList(maxCandidatesForLlm, relevant.size());
            for (NormalizedCandidate c : overflow) {
                log.info("CheapRelevanceFilter CAPPED (over max={}): [{}] '{}'",
                        maxCandidatesForLlm, c.getSource(), c.getTitle());
                persistRejection(c, agentId, tickId,
                        "Capped: too many candidates for LLM this tick (max=" + maxCandidatesForLlm + ")");
            }
            capped = new ArrayList<>(relevant.subList(0, maxCandidatesForLlm));
        } else {
            capped = relevant;
        }

        log.info("CheapRelevanceFilter: {} candidates forwarded to LLM scoring (irrelevant={}, capped={})",
                capped.size(), irrelevant.size(),
                relevant.size() > maxCandidatesForLlm ? relevant.size() - maxCandidatesForLlm : 0);

        return capped;
    }

    // ----- internals -----

    /**
     * Returns true if the candidate has at least one ML/AI-security keyword match.
     * Uses {@code FILTER_KEYWORDS} (not bare {@code PersonaProfile.STABLE_INTERESTS})
     * to avoid passing generic CVEs or incidents with no AI/ML context.
     * Tier A sources (NVD, arXiv) still need a keyword match — the Tier A bonus applies
     * only in the ranking step, not as an unconditional filter bypass.
     */
    public boolean hasRelevanceSignal(NormalizedCandidate c) {
        String haystack = ((c.getTitle() != null ? c.getTitle() : "") + " "
                + (c.getSummary() != null ? c.getSummary() : "")).toLowerCase();

        return FILTER_KEYWORDS.stream()
                .anyMatch(keyword -> haystack.contains(keyword.toLowerCase()));
    }

    /**
     * Computes a ranking score for a candidate that has already passed the relevance check.
     * Higher is better. Components:
     * <ul>
     *   <li>+3 per high-value AI/LLM security keyword match in title (capped at 5 matches)</li>
     *   <li>+1 per regular STABLE_INTERESTS keyword match in title+summary (capped at 5)</li>
     *   <li>+20 if Tier A (credibility bonus, not unconditional relevance)</li>
     *   <li>recency: seconds since epoch / 86400 * 0.001 (tiny recency tiebreaker)</li>
     * </ul>
     */
    private double rankScore(NormalizedCandidate c) {
        String titleLower = (c.getTitle() != null ? c.getTitle() : "").toLowerCase();
        String haystack = (titleLower + " " + (c.getSummary() != null ? c.getSummary() : "")).toLowerCase();

        // Count high-value keyword hits in title (title matters more)
        long highValueHits = HIGH_VALUE_KEYWORDS.stream()
                .filter(kw -> titleLower.contains(kw))
                .count();
        double score = Math.min(highValueHits, 5) * 3.0;

        // Count general stable-interest keyword hits in title+summary
        long generalHits = PersonaProfile.STABLE_INTERESTS.stream()
                .filter(kw -> haystack.contains(kw.toLowerCase()))
                .count();
        score += Math.min(generalHits, 5) * 1.0;

        // Tier A credibility bonus (not a bypass, just a bonus)
        if ("A".equals(c.getCredibilityTier())) {
            score += 20.0;
        } else if ("B".equals(c.getCredibilityTier())) {
            score += 5.0;
        }

        // Small recency component: days-since-epoch / 1000 (only a tiebreaker)
        if (c.getPublishedAt() != null) {
            score += c.getPublishedAt().getEpochSecond() / 86400.0 / 1000.0;
        }

        return score;
    }

    private void persistRejection(NormalizedCandidate c, UUID agentId, UUID tickId, String reason) {
        TopicCandidate tc = new TopicCandidate();
        tc.setId(UUID.randomUUID());
        tc.setAgentId(agentId);
        tc.setTickId(tickId);
        tc.setSource(c.getSource());
        tc.setRawTitle(c.getTitle());
        tc.setRawUrl(c.getUrl());
        tc.setCredibilityTier(c.getCredibilityTier());
        tc.setEditorialScore(null);
        tc.setConfidence(null);
        tc.setPersonaAlignmentPassed(null);
        tc.setDecision("REJECTED");
        tc.setDecisionReason(reason);
        tc.setDecisionStage("CHEAP_RELEVANCE_FILTER");
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }
}
