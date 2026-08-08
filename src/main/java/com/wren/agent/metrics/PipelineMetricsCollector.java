package com.wren.agent.metrics;

import com.wren.agent.domain.entity.PipelineMetricsRecord;
import com.wren.agent.domain.repository.PipelineMetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class PipelineMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(PipelineMetricsCollector.class);

    private final PipelineMetricsRepository metricsRepository;

    public PipelineMetricsCollector(PipelineMetricsRepository metricsRepository) {
        this.metricsRepository = metricsRepository;
    }

    public TickMetrics startTick(UUID agentId, UUID tickId) {
        PipelineMetricsRecord record = new PipelineMetricsRecord();
        record.setId(UUID.randomUUID());
        record.setAgentId(agentId);
        record.setTickId(tickId);
        record.setTickStartedAt(Instant.now());
        record.setCandidatesDiscovered(0);
        record.setCandidatesRejected(0);
        record.setCandidatesAccepted(0);
        record.setAvgEditorialScore(0.0);
        record.setLlmProviderFailovers(0);
        record.setApiFailures(0);
        record.setSelfCritiqueRevisions(0);
        record.setSelfCritiqueRejections(0);
        record.setResultedPostId(null);
        return new TickMetrics(record, metricsRepository);
    }

    public static class TickMetrics {
        private final PipelineMetricsRecord record;
        private final PipelineMetricsRepository metricsRepository;
        private int candidatesDiscovered;
        private int candidatesRejected;
        private int candidatesAccepted;
        private int llmFailoverCount;
        private long totalLatencyMs;
        private int callCount;
        private String lastProviderUsed;
        private int selfCritiqueRevisions;
        private int selfCritiqueRejections;

        public TickMetrics(PipelineMetricsRecord record, PipelineMetricsRepository metricsRepository) {
            this.record = record;
            this.metricsRepository = metricsRepository;
        }

        public void recordDiscovery(int count) {
            this.candidatesDiscovered = count;
            this.record.setCandidatesDiscovered(count);
        }

        public void recordRejected(int count) {
            this.candidatesRejected = count;
            this.record.setCandidatesRejected(count);
        }

        public void incrementRejected(int count) {
            this.candidatesRejected += count;
            this.record.setCandidatesRejected(candidatesRejected);
        }

        public void recordAccepted(int count) {
            this.candidatesAccepted = count;
            this.record.setCandidatesAccepted(count);
        }

        public void recordLlmCall(String providerName, long latencyMs, int failoverCount) {
            this.lastProviderUsed = providerName;
            this.totalLatencyMs += latencyMs;
            this.callCount++;
            this.llmFailoverCount += failoverCount;
            this.record.setLlmProviderUsed(providerName);
            this.record.setLlmLatencyMs((int) (totalLatencyMs / callCount));
            this.record.setLlmProviderFailovers(llmFailoverCount);
        }

        public void recordApiFailure() {
            this.record.setApiFailures((this.record.getApiFailures() != null ? this.record.getApiFailures() : 0) + 1);
        }

        public void recordSelfCritiqueRevision() {
            this.selfCritiqueRevisions++;
            this.record.setSelfCritiqueRevisions(selfCritiqueRevisions);
        }

        public void recordSelfCritiqueRejection() {
            this.selfCritiqueRejections++;
            this.record.setSelfCritiqueRejections(selfCritiqueRejections);
        }

        public void recordAvgEditorialScore(double avgScore) {
            this.record.setAvgEditorialScore(avgScore);
        }

        public void recordResultedPostId(String postId) {
            this.record.setResultedPostId(postId);
        }

        @Transactional
        public void complete(String resultedPostId) {
            recordResultedPostId(resultedPostId);
            Instant now = Instant.now();
            record.setTickCompletedAt(now);
            log.info("PipelineMetricsCollector: saving metrics for tick={} discovered={} rejected={} accepted={} provider={}",
                    record.getTickId(), record.getCandidatesDiscovered(),
                    record.getCandidatesRejected(), record.getCandidatesAccepted(),
                    record.getLlmProviderUsed());
            metricsRepository.save(record);
        }

        @Transactional
        public void fail() {
            Instant now = Instant.now();
            record.setTickCompletedAt(now);
            record.setApiFailures((record.getApiFailures() != null ? record.getApiFailures() : 0) + 1);
            metricsRepository.save(record);
        }

        public PipelineMetricsRecord getRecord() {
            return record;
        }
    }
}
