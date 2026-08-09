package com.wren.agent.pipeline.stages;

import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.memory.MemoryRetrievalService;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DeduplicationStage {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationStage.class);

    private final MemoryRetrievalService memoryRetrievalService;
    private final TopicCandidateRepository topicCandidateRepository;

    public DeduplicationStage(MemoryRetrievalService memoryRetrievalService,
                              TopicCandidateRepository topicCandidateRepository) {
        this.memoryRetrievalService = memoryRetrievalService;
        this.topicCandidateRepository = topicCandidateRepository;
    }

    public List<NormalizedCandidate> deduplicate(List<NormalizedCandidate> candidates, UUID agentId) {
        return deduplicate(candidates, agentId, null);
    }

    /**
     * Multi-layer deduplication:
     * 1. Intra-tick: collapse near-identical topic keys and identical URLs from this tick
     * 2. Cross-time: reject candidates whose URL or topic key closely matches previously published posts
     */
    public List<NormalizedCandidate> deduplicate(List<NormalizedCandidate> candidates, UUID agentId, UUID tickId) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // --- Layer 1: Intra-tick dedup by topic_key and clean URL ---
        Map<String, NormalizedCandidate> seenKeys = new LinkedHashMap<>();
        Set<String> seenUrls = new HashSet<>();
        List<NormalizedCandidate> uniqueIntraTick = new ArrayList<>();

        for (NormalizedCandidate c : candidates) {
            String cleanUrl = cleanUrl(c.getUrl());
            if (seenKeys.containsKey(c.getTopicKey())) {
                log.info("DeduplicationStage: Intra-tick duplicate topic key dropped: '{}' (key={})", c.getTitle(), c.getTopicKey());
                persistDecision(c, agentId, tickId, "DEDUPLICATION", "REJECTED", "Intra-tick duplicate topic key: " + c.getTopicKey());
            } else if (!cleanUrl.isEmpty() && seenUrls.contains(cleanUrl)) {
                log.info("DeduplicationStage: Intra-tick duplicate URL dropped: '{}' (url={})", c.getTitle(), c.getUrl());
                persistDecision(c, agentId, tickId, "DEDUPLICATION", "REJECTED", "Intra-tick duplicate URL: " + c.getUrl());
            } else {
                seenKeys.put(c.getTopicKey(), c);
                if (!cleanUrl.isEmpty()) {
                    seenUrls.add(cleanUrl);
                }
                uniqueIntraTick.add(c);
            }
        }

        // --- Layer 2: Cross-time memory / post match ---
        List<NormalizedCandidate> finalUnique = new ArrayList<>();

        if (agentId != null) {
            for (NormalizedCandidate c : uniqueIntraTick) {
                boolean publishedUrl = memoryRetrievalService.hasPublishedUrl(agentId, c.getUrl());
                boolean exactTopicMatch = memoryRetrievalService.hasSeenTopicKey(agentId, c.getTopicKey());
                boolean fuzzyTopicMatch = !exactTopicMatch && memoryRetrievalService.hasFuzzyTopicMatch(agentId, c.getTopicKey());

                if (publishedUrl) {
                    log.info("DeduplicationStage: Cross-time match dropped (published URL): '{}'", c.getTitle());
                    persistDecision(c, agentId, tickId, "DEDUPLICATION", "REJECTED", "URL previously published in a post: " + c.getUrl());
                } else if (exactTopicMatch) {
                    log.info("DeduplicationStage: Cross-time match dropped (exact topic key): '{}'", c.getTitle());
                    persistDecision(c, agentId, tickId, "DEDUPLICATION", "REJECTED", "Topic key previously published: " + c.getTopicKey());
                } else if (fuzzyTopicMatch) {
                    log.info("DeduplicationStage: Cross-time match dropped (fuzzy topic match): '{}'", c.getTitle());
                    persistDecision(c, agentId, tickId, "DEDUPLICATION", "REJECTED", "Fuzzy duplicate of previously published post topic");
                } else {
                    finalUnique.add(c);
                }
            }
        } else {
            finalUnique.addAll(uniqueIntraTick);
        }

        log.info("DeduplicationStage: {} candidates in, {} unique out", candidates.size(), finalUnique.size());
        return finalUnique;
    }

    private void persistDecision(NormalizedCandidate c, UUID agentId, UUID tickId,
                                 String decisionStage, String decision, String decisionReason) {
        if (agentId == null || tickId == null || topicCandidateRepository == null) {
            return;
        }
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
        tc.setDecision(decision);
        tc.setDecisionReason(decisionReason);
        tc.setDecisionStage(decisionStage);
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }

    private String cleanUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("^https?://", "").replaceAll("/$", "").trim().toLowerCase();
    }
}
