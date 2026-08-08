package com.wren.agent.pipeline.model;

import com.wren.agent.pipeline.model.ScoredCandidate;

import java.util.List;

public class PublishDecision {

    private final ScoredCandidate winner;
    private final List<ScoredCandidate> rankedCandidates;

    public PublishDecision(ScoredCandidate winner, List<ScoredCandidate> rankedCandidates) {
        this.winner = winner;
        this.rankedCandidates = rankedCandidates;
    }

    public ScoredCandidate getWinner() {
        return winner;
    }

    public List<ScoredCandidate> getRankedCandidates() {
        return rankedCandidates;
    }

    public boolean hasWinner() {
        return winner != null;
    }
}