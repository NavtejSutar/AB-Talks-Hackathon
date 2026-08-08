package com.wren.agent.pipeline.stages;

import com.wren.agent.pipeline.model.PublishDecision;
import com.wren.agent.pipeline.model.ScoredCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class PublishDecisionStage {

    private static final Logger log = LoggerFactory.getLogger(PublishDecisionStage.class);

    /**
     * Selects exactly ONE winner for publishing.
     *
     * Tie-break order (per user design decision):
     *   1. Credibility tier: A > B
     *   2. Recency: most recent publishedAt first
     *   3. Editorial score: higher is better
     */
    public PublishDecision decide(List<ScoredCandidate> candidates) {
        if (candidates.isEmpty()) {
            log.info("PublishDecisionStage: no candidates — no posts this tick");
            return new PublishDecision(null, List.of());
        }

        List<ScoredCandidate> sorted = new ArrayList<>(candidates);

        // Comparator: A > B, then newer publishedAt, then higher score
        Comparator<ScoredCandidate> tieBreak = Comparator
                .<ScoredCandidate, Integer>comparing(sc -> tierRank(sc.getCredibilityTier()))
                .reversed()
                .thenComparing(sc -> sc.getPublishedAt() == null ? 0L : sc.getPublishedAt().getEpochSecond(), Comparator.reverseOrder())
                .thenComparingInt(ScoredCandidate::getEditorialScore).reversed();

        sorted.sort(tieBreak);

        ScoredCandidate winner = sorted.get(0);

        log.info("PublishDecisionStage: {} candidates -> winner: '{}' [Tier {}] score={}",
                candidates.size(), winner.getCandidate().getTitle(),
                winner.getCredibilityTier(), winner.getEditorialScore());

        return new PublishDecision(winner, sorted);
    }

    private int tierRank(String tier) {
        return switch (tier == null ? "" : tier) {
            case "A" -> 2;
            case "B" -> 1;
            default -> 0;
        };
    }
}