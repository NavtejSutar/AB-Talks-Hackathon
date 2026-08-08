package com.wren.agent.pipeline.stages;

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

    public DeduplicationStage(MemoryRetrievalService memoryRetrievalService) {
        this.memoryRetrievalService = memoryRetrievalService;
    }

    /**
     * Two-layer deduplication:
     * 1. Intra-tick: collapse near-identical topic keys from this tick
     * 2. Cross-time: flag candidates whose topic key closely matches memory entries as possibleFollowup=true
     *    (NOT auto-rejected — that decision belongs to EditorialScoreStage).
     */
    public List<NormalizedCandidate> deduplicate(List<NormalizedCandidate> candidates, UUID agentId) {
        // --- Layer 1: Intra-tick dedup by topic_key ---
        Map<String, NormalizedCandidate> seenKeys = new LinkedHashMap<>();
        for (NormalizedCandidate c : candidates) {
            if (!seenKeys.containsKey(c.getTopicKey())) {
                seenKeys.put(c.getTopicKey(), c);
            } else {
                log.debug("Intra-tick duplicate dropped: '{}' (key={})", c.getTitle(), c.getTopicKey());
            }
        }

        List<NormalizedCandidate> unique = new ArrayList<>(seenKeys.values());

        // --- Layer 2: Cross-time memory match ---
        if (agentId != null) {
            for (NormalizedCandidate c : unique) {
                boolean exactMatch = memoryRetrievalService.hasSeenTopicKey(agentId, c.getTopicKey());
                boolean fuzzyMatch = !exactMatch && memoryRetrievalService.hasFuzzyTopicMatch(agentId, c.getTopicKey());

                if (exactMatch || fuzzyMatch) {
                    c.setPossibleFollowup(true);
                    log.info("Cross-time match flagged as possibleFollowup: '{}' ({})", c.getTitle(), exactMatch ? "exact" : "fuzzy");
                }
            }
        }

        log.info("DeduplicationStage: {} candidates in, {} unique out", candidates.size(), unique.size());
        return unique;
    }
}
