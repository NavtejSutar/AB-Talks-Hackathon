package com.wren.agent.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory_entries")
public class MemoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "post_id")
    private String postId;

    @Column(name = "topic_key", nullable = false)
    private String topicKey;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "opinion_stance", columnDefinition = "TEXT")
    private String opinionStance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public MemoryEntry() {}

    public MemoryEntry(UUID agentId, String postId, String topicKey, String summary, String opinionStance) {
        this.agentId = agentId;
        this.postId = postId;
        this.topicKey = topicKey;
        this.summary = summary;
        this.opinionStance = opinionStance;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getOpinionStance() { return opinionStance; }
    public void setOpinionStance(String opinionStance) { this.opinionStance = opinionStance; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
