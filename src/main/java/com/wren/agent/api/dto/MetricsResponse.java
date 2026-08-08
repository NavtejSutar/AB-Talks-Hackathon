package com.wren.agent.api.dto;

import com.wren.agent.domain.entity.PipelineMetricsRecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class MetricsResponse {

    private UUID agentId;
    private int totalTicks;
    private int candidatesDiscovered;
    private int candidatesRejected;
    private int candidatesAccepted;
    private double avgEditorialScore;
    private int totalFailovers;
    private int totalSelfCritiqueRevisions;
    private int totalSelfCritiqueRejections;
    private Instant lastTickAt;
    private Instant nextTickAt;
    private List<PipelineMetricsRecord> recentMetrics;

    public MetricsResponse() {}

    public MetricsResponse(
            UUID agentId,
            int totalTicks,
            int candidatesDiscovered,
            int candidatesRejected,
            int candidatesAccepted,
            double avgEditorialScore,
            int totalFailovers,
            int totalSelfCritiqueRevisions,
            int totalSelfCritiqueRejections,
            Instant lastTickAt,
            Instant nextTickAt,
            List<PipelineMetricsRecord> recentMetrics) {
        this.agentId = agentId;
        this.totalTicks = totalTicks;
        this.candidatesDiscovered = candidatesDiscovered;
        this.candidatesRejected = candidatesRejected;
        this.candidatesAccepted = candidatesAccepted;
        this.avgEditorialScore = avgEditorialScore;
        this.totalFailovers = totalFailovers;
        this.totalSelfCritiqueRevisions = totalSelfCritiqueRevisions;
        this.totalSelfCritiqueRejections = totalSelfCritiqueRejections;
        this.lastTickAt = lastTickAt;
        this.nextTickAt = nextTickAt;
        this.recentMetrics = recentMetrics;
    }

    public UUID getAgentId() { return agentId; }
    public int getTotalTicks() { return totalTicks; }
    public int getCandidatesDiscovered() { return candidatesDiscovered; }
    public int getCandidatesRejected() { return candidatesRejected; }
    public int getCandidatesAccepted() { return candidatesAccepted; }
    public double getAvgEditorialScore() { return avgEditorialScore; }
    public int getTotalFailovers() { return totalFailovers; }
    public int getTotalSelfCritiqueRevisions() { return totalSelfCritiqueRevisions; }
    public int getTotalSelfCritiqueRejections() { return totalSelfCritiqueRejections; }
    public Instant getLastTickAt() { return lastTickAt; }
    public Instant getNextTickAt() { return nextTickAt; }
    public List<PipelineMetricsRecord> getRecentMetrics() { return recentMetrics; }
}
