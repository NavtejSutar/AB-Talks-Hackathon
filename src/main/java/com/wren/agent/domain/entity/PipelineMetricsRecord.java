package com.wren.agent.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_metrics")
public class PipelineMetricsRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "tick_id", nullable = false, unique = true)
    private UUID tickId;

    @Column(name = "tick_started_at", nullable = false)
    private Instant tickStartedAt;

    @Column(name = "tick_completed_at")
    private Instant tickCompletedAt;

    @Column(name = "candidates_discovered")
    private Integer candidatesDiscovered = 0;

    @Column(name = "candidates_rejected")
    private Integer candidatesRejected = 0;

    @Column(name = "candidates_accepted")
    private Integer candidatesAccepted = 0;

    @Column(name = "avg_editorial_score")
    private Double avgEditorialScore;

    @Column(name = "llm_provider_used")
    private String llmProviderUsed;

    @Column(name = "llm_provider_failovers")
    private Integer llmProviderFailovers = 0;

    @Column(name = "llm_latency_ms")
    private Integer llmLatencyMs;

    @Column(name = "api_failures")
    private Integer apiFailures = 0;

    @Column(name = "self_critique_revisions")
    private Integer selfCritiqueRevisions = 0;

    @Column(name = "self_critique_rejections")
    private Integer selfCritiqueRejections = 0;

    @Column(name = "resulted_post_id")
    private String resultedPostId;

    public PipelineMetricsRecord() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public UUID getTickId() { return tickId; }
    public void setTickId(UUID tickId) { this.tickId = tickId; }

    public Instant getTickStartedAt() { return tickStartedAt; }
    public void setTickStartedAt(Instant tickStartedAt) { this.tickStartedAt = tickStartedAt; }

    public Instant getTickCompletedAt() { return tickCompletedAt; }
    public void setTickCompletedAt(Instant tickCompletedAt) { this.tickCompletedAt = tickCompletedAt; }

    public Integer getCandidatesDiscovered() { return candidatesDiscovered; }
    public void setCandidatesDiscovered(Integer candidatesDiscovered) { this.candidatesDiscovered = candidatesDiscovered; }

    public Integer getCandidatesRejected() { return candidatesRejected; }
    public void setCandidatesRejected(Integer candidatesRejected) { this.candidatesRejected = candidatesRejected; }

    public Integer getCandidatesAccepted() { return candidatesAccepted; }
    public void setCandidatesAccepted(Integer candidatesAccepted) { this.candidatesAccepted = candidatesAccepted; }

    public Double getAvgEditorialScore() { return avgEditorialScore; }
    public void setAvgEditorialScore(Double avgEditorialScore) { this.avgEditorialScore = avgEditorialScore; }

    public String getLlmProviderUsed() { return llmProviderUsed; }
    public void setLlmProviderUsed(String llmProviderUsed) { this.llmProviderUsed = llmProviderUsed; }

    public Integer getLlmProviderFailovers() { return llmProviderFailovers; }
    public void setLlmProviderFailovers(Integer llmProviderFailovers) { this.llmProviderFailovers = llmProviderFailovers; }

    public Integer getLlmLatencyMs() { return llmLatencyMs; }
    public void setLlmLatencyMs(Integer llmLatencyMs) { this.llmLatencyMs = llmLatencyMs; }

    public Integer getApiFailures() { return apiFailures; }
    public void setApiFailures(Integer apiFailures) { this.apiFailures = apiFailures; }

    public Integer getSelfCritiqueRevisions() { return selfCritiqueRevisions; }
    public void setSelfCritiqueRevisions(Integer selfCritiqueRevisions) { this.selfCritiqueRevisions = selfCritiqueRevisions; }

    public Integer getSelfCritiqueRejections() { return selfCritiqueRejections; }
    public void setSelfCritiqueRejections(Integer selfCritiqueRejections) { this.selfCritiqueRejections = selfCritiqueRejections; }

    public String getResultedPostId() { return resultedPostId; }
    public void setResultedPostId(String resultedPostId) { this.resultedPostId = resultedPostId; }
}
