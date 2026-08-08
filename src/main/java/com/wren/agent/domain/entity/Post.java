package com.wren.agent.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class Post {

    @Id
    private String id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Headline (short punchy one-liner) */
    @Column(name = "headline", columnDefinition = "TEXT")
    private String headline;

    /** Body text */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Legacy text column — stores full combined content if headline/body not set */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "sources", nullable = false)
    private List<String> sources = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "hashtags")
    private List<String> hashtags = new ArrayList<>();

    @Column(name = "topic_key", nullable = false)
    private String topicKey;

    @Column(name = "credibility_tier")
    private String credibilityTier;

    @Column(name = "is_followup_of")
    private String isFollowupOf;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "editorial_score")
    private Double editorialScore;

    @Column(name = "llm_provider_used")
    private String llmProviderUsed;

    @Column(name = "self_critique_verdict")
    private String selfCritiqueVerdict;

    public Post() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }

    public List<String> getHashtags() { return hashtags; }
    public void setHashtags(List<String> hashtags) { this.hashtags = hashtags; }

    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }

    public String getCredibilityTier() { return credibilityTier; }
    public void setCredibilityTier(String credibilityTier) { this.credibilityTier = credibilityTier; }

    public String getIsFollowupOf() { return isFollowupOf; }
    public void setIsFollowupOf(String isFollowupOf) { this.isFollowupOf = isFollowupOf; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Double getEditorialScore() { return editorialScore; }
    public void setEditorialScore(Double editorialScore) { this.editorialScore = editorialScore; }

    public String getLlmProviderUsed() { return llmProviderUsed; }
    public void setLlmProviderUsed(String llmProviderUsed) { this.llmProviderUsed = llmProviderUsed; }

    public String getSelfCritiqueVerdict() { return selfCritiqueVerdict; }
    public void setSelfCritiqueVerdict(String selfCritiqueVerdict) { this.selfCritiqueVerdict = selfCritiqueVerdict; }
}
