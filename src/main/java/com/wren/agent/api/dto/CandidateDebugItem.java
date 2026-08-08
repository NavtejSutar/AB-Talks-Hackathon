package com.wren.agent.api.dto;

import com.wren.agent.domain.entity.TopicCandidate;

import java.time.Instant;
import java.util.UUID;

public class CandidateDebugItem {

    private UUID id;
    private UUID tickId;
    private Instant discoveredAt;
    private String source;
    private String rawTitle;
    private String rawUrl;
    private String credibilityTier;
    private Double editorialScore;
    private Double confidence;
    private String decision;
    private String decisionReason;
    private String decisionStage;
    private String resultedPostId;

    public CandidateDebugItem() {}

    public CandidateDebugItem(TopicCandidate candidate) {
        this.id = candidate.getId();
        this.tickId = candidate.getTickId();
        this.discoveredAt = candidate.getDiscoveredAt();
        this.source = candidate.getSource();
        this.rawTitle = candidate.getRawTitle();
        this.rawUrl = candidate.getRawUrl();
        this.credibilityTier = candidate.getCredibilityTier();
        this.editorialScore = candidate.getEditorialScore();
        this.confidence = candidate.getConfidence();
        this.decision = candidate.getDecision();
        this.decisionReason = candidate.getDecisionReason();
        this.decisionStage = candidate.getDecisionStage();
        this.resultedPostId = candidate.getResultedPostId();
    }

    public UUID getId() { return id; }
    public UUID getTickId() { return tickId; }
    public Instant getDiscoveredAt() { return discoveredAt; }
    public String getSource() { return source; }
    public String getRawTitle() { return rawTitle; }
    public String getRawUrl() { return rawUrl; }
    public String getCredibilityTier() { return credibilityTier; }
    public Double getEditorialScore() { return editorialScore; }
    public Double getConfidence() { return confidence; }
    public String getDecision() { return decision; }
    public String getDecisionReason() { return decisionReason; }
    public String getDecisionStage() { return decisionStage; }
    public String getResultedPostId() { return resultedPostId; }
}
