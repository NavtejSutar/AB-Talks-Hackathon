package com.wren.agent.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "topic_candidates")
public class TopicCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "tick_id", nullable = false)
    private UUID tickId;

    @Column(name = "discovered_at", nullable = false, updatable = false)
    private Instant discoveredAt = Instant.now();

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "raw_title", nullable = false, columnDefinition = "TEXT")
    private String rawTitle;

    @Column(name = "raw_url", nullable = false, columnDefinition = "TEXT")
    private String rawUrl;

    @Column(name = "credibility_tier")
    private String credibilityTier;

    @Column(name = "editorial_score")
    private Double editorialScore;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "persona_alignment_passed")
    private Boolean personaAlignmentPassed;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "decision_reason", nullable = false, columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "decision_stage", nullable = false)
    private String decisionStage;

    @Column(name = "resulted_post_id")
    private String resultedPostId;

    public TopicCandidate() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public UUID getTickId() { return tickId; }
    public void setTickId(UUID tickId) { this.tickId = tickId; }

    public Instant getDiscoveredAt() { return discoveredAt; }
    public void setDiscoveredAt(Instant discoveredAt) { this.discoveredAt = discoveredAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getRawTitle() { return rawTitle; }
    public void setRawTitle(String rawTitle) { this.rawTitle = rawTitle; }

    public String getRawUrl() { return rawUrl; }
    public void setRawUrl(String rawUrl) { this.rawUrl = rawUrl; }

    public String getCredibilityTier() { return credibilityTier; }
    public void setCredibilityTier(String credibilityTier) { this.credibilityTier = credibilityTier; }

    public Double getEditorialScore() { return editorialScore; }
    public void setEditorialScore(Double editorialScore) { this.editorialScore = editorialScore; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Boolean getPersonaAlignmentPassed() { return personaAlignmentPassed; }
    public void setPersonaAlignmentPassed(Boolean personaAlignmentPassed) { this.personaAlignmentPassed = personaAlignmentPassed; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public String getDecisionStage() { return decisionStage; }
    public void setDecisionStage(String decisionStage) { this.decisionStage = decisionStage; }

    public String getResultedPostId() { return resultedPostId; }
    public void setResultedPostId(String resultedPostId) { this.resultedPostId = resultedPostId; }
}
