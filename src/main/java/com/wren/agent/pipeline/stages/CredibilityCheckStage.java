package com.wren.agent.pipeline.stages;

import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CredibilityCheckStage {

    private static final Logger log = LoggerFactory.getLogger(CredibilityCheckStage.class);

    // Tier A sources — trusted origins (used as source name prefixes from adapters)
    private static final Set<String> TIER_A_SOURCES = Set.of("arxiv", "nvd");

    // Tier B sources — reliable but require stronger editorial score
    private static final Set<String> TIER_B_SOURCES = Set.of("hn", "github");

    // Clickbait / low-quality title patterns → Tier C → auto-reject
    private static final List<Pattern> CLICKBAIT_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(shocking|unbelievable|you won't believe|mind-blowing|game[- ]changing|revolutionary|amazing|incredible)\\b"),
            Pattern.compile("(?i)\\b(top \\d+ reasons|\\d+ things you)\\b"),
            Pattern.compile("(?i)\\b(AI is changing|AI will replace|future of AI)\\b")
    );

    // Minimum title length to be considered substantive
    private static final int MIN_TITLE_LENGTH = 10;

    private final TopicCandidateRepository topicCandidateRepository;

    public CredibilityCheckStage(TopicCandidateRepository topicCandidateRepository) {
        this.topicCandidateRepository = topicCandidateRepository;
    }

    /**
     * Assigns A/B/C credibility tier. Tier C candidates are filtered out (never reach LLM calls).
     * Returns only Tier A and B candidates.
     * Persists ALL decisions (including Tier C rejections) to topic_candidates.
     */
    @Transactional
    public List<NormalizedCandidate> assessCredibility(List<NormalizedCandidate> candidates, UUID agentId, UUID tickId) {
        List<NormalizedCandidate> eligible = new ArrayList<>();

        for (NormalizedCandidate c : candidates) {
            String tier = computeTier(c);
            c.setCredibilityTier(tier);

            if ("C".equals(tier)) {
                log.info("CredibilityCheck REJECTED (Tier C): '{}' [source={}]", c.getTitle(), c.getSource());
                persistDecision(c, agentId, tickId, "C", "REJECTED", "CREDIBILITY_CHECK",
                        "Auto-rejected: credibility Tier C (source=" + c.getSource() + ", title=" + c.getTitle() + ")");
            } else {
                log.debug("CredibilityCheck ELIGIBLE (Tier {}): '{}'", tier, c.getTitle());
                eligible.add(c);
                persistDecision(c, agentId, tickId, tier, "ACCEPTED", "CREDIBILITY_CHECK",
                        "Passed credibility check: Tier " + tier);
            }
        }

        log.info("CredibilityCheckStage: {}/{} candidates passed (Tier A/B)", eligible.size(), candidates.size());
        return eligible;
    }

    private void persistDecision(NormalizedCandidate c, UUID agentId, UUID tickId,
                                 String credibilityTier, String decision, String decisionStage, String decisionReason) {
        TopicCandidate tc = new TopicCandidate();
        tc.setId(java.util.UUID.randomUUID());
        tc.setAgentId(agentId);
        tc.setTickId(tickId);
        tc.setSource(c.getSource());
        tc.setRawTitle(c.getTitle());
        tc.setRawUrl(c.getUrl());
        tc.setCredibilityTier(credibilityTier);
        tc.setEditorialScore(null);
        tc.setConfidence(null);
        tc.setPersonaAlignmentPassed(null);
        tc.setDecision(decision);
        tc.setDecisionReason(decisionReason);
        tc.setDecisionStage(decisionStage);
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }

    private String computeTier(NormalizedCandidate c) {
        // Reject very short titles
        if (c.getTitle() == null || c.getTitle().length() < MIN_TITLE_LENGTH) {
            return "C";
        }

        // Reject clickbait titles
        for (Pattern p : CLICKBAIT_PATTERNS) {
            if (p.matcher(c.getTitle()).find()) {
                return "C";
            }
        }

        String source = c.getSource() != null ? c.getSource().toLowerCase() : "";

        if (TIER_A_SOURCES.contains(source)) return "A";
        if (TIER_B_SOURCES.contains(source)) return "B";

        // Unknown source defaults to C
        return "C";
    }
}