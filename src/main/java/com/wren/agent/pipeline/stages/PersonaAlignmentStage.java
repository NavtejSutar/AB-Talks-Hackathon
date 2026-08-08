package com.wren.agent.pipeline.stages;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.persona.PersonaProfile;
import com.wren.agent.pipeline.model.ScoredCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PersonaAlignmentStage {

    private static final Logger log = LoggerFactory.getLogger(PersonaAlignmentStage.class);

    private final TopicCandidateRepository topicCandidateRepository;

    public PersonaAlignmentStage(TopicCandidateRepository topicCandidateRepository) {
        this.topicCandidateRepository = topicCandidateRepository;
    }

    /**
     * Filters candidates to those that fit Wren's persona and voice.
     * Uses rule-based exclusion list check (cheap, no LLM call).
     * Candidates matching exclusion keywords are rejected.
     * Persists ALL decisions to topic_candidates.
     */
    @Transactional
    public List<ScoredCandidate> filter(List<ScoredCandidate> candidates, Agent agent, UUID tickId) {
        List<ScoredCandidate> aligned = new ArrayList<>();

        for (ScoredCandidate sc : candidates) {
            try {
                boolean fits = check(sc, agent, tickId);
                if (fits) {
                    aligned.add(sc);
                    log.info("PersonaAlignment ALIGNED: '{}'", sc.getCandidate().getTitle());
                } else {
                    log.info("PersonaAlignment NOT ALIGNED (dropped): '{}'", sc.getCandidate().getTitle());
                }
            } catch (Exception e) {
                // On failure: include candidate conservatively, WritingStage will still use system prompt
                log.warn("PersonaAlignment check failed for '{}', including conservatively: {}", sc.getCandidate().getTitle(), e.getMessage());
                aligned.add(sc);
                persistDecision(sc, agent.getId(), tickId, true, "PERSONA_ALIGNMENT",
                        "Check failed, included conservatively: " + e.getMessage());
            }
        }

        log.info("PersonaAlignmentStage: {}/{} candidates aligned", aligned.size(), candidates.size());
        return aligned;
    }

    private boolean check(ScoredCandidate sc, Agent agent, UUID tickId) {
        String title = sc.getCandidate().getTitle().toLowerCase();
        String summary = sc.getCandidate().getSummary() != null ? sc.getCandidate().getSummary().toLowerCase() : "";

        // Check against exclusion keywords from PersonaProfile
        for (String keyword : PersonaProfile.EXCLUSION_KEYWORDS) {
            if (title.contains(keyword.toLowerCase()) || summary.contains(keyword.toLowerCase())) {
                String rationale = "Rejected by persona exclusion rule: matched keyword '" + keyword + "'";
                sc.setPersonaAlignRationale(rationale);
                persistDecision(sc, agent.getId(), tickId, false, "PERSONA_ALIGNMENT", rationale);
                return false;
            }
        }

        // Check for stable interests - at least some overlap expected
        boolean hasInterestMatch = PersonaProfile.STABLE_INTERESTS.stream()
                .anyMatch(interest -> title.contains(interest.toLowerCase()) || summary.contains(interest.toLowerCase()));

        if (!hasInterestMatch) {
            String rationale = "No alignment with persona's stable interests";
            sc.setPersonaAlignRationale(rationale);
            persistDecision(sc, agent.getId(), tickId, false, "PERSONA_ALIGNMENT", rationale);
            return false;
        }

        sc.setPersonaAlignRationale("Aligned with persona interests and no exclusion keywords matched");
        persistDecision(sc, agent.getId(), tickId, true, "PERSONA_ALIGNMENT",
                "Aligned with persona interests and no exclusion keywords matched");
        return true;
    }

    private void persistDecision(ScoredCandidate sc, UUID agentId, UUID tickId,
                                 boolean passed, String decisionStage, String decisionReason) {
        TopicCandidate tc = new TopicCandidate();
        tc.setId(java.util.UUID.randomUUID());
        tc.setAgentId(agentId);
        tc.setTickId(tickId);
        tc.setSource(sc.getCandidate().getSource());
        tc.setRawTitle(sc.getCandidate().getTitle());
        tc.setRawUrl(sc.getCandidate().getUrl());
        tc.setCredibilityTier(sc.getCredibilityTier());
        tc.setEditorialScore((double) sc.getEditorialScore());
        tc.setConfidence((double) sc.getConfidence());
        tc.setPersonaAlignmentPassed(passed);
        tc.setDecision(passed ? "ACCEPTED" : "REJECTED");
        tc.setDecisionReason(decisionReason);
        tc.setDecisionStage(decisionStage);
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }
}