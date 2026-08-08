package com.wren.agent.pipeline;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.metrics.PipelineMetricsCollector;
import com.wren.agent.pipeline.model.DraftPost;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import com.wren.agent.pipeline.model.PublishDecision;
import com.wren.agent.pipeline.model.RawCandidate;
import com.wren.agent.pipeline.model.ScoredCandidate;
import com.wren.agent.pipeline.stages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final DiscoveryStage discoveryStage;
    private final NormalizationStage normalizationStage;
    private final DeduplicationStage deduplicationStage;
    private final CredibilityCheckStage credibilityCheckStage;
    private final EditorialScoreStage editorialScoreStage;
    private final PersonaAlignmentStage personaAlignmentStage;
    private final PublishDecisionStage publishDecisionStage;
    private final WritingStage writingStage;
    private final SelfCritiqueStage selfCritiqueStage;
    private final MemoryWriteStage memoryWriteStage;
    private final AgentRepository agentRepository;
    private final TopicCandidateRepository topicCandidateRepository;
    private final PipelineMetricsCollector metricsCollector;

    public PipelineOrchestrator(
            DiscoveryStage discoveryStage,
            NormalizationStage normalizationStage,
            DeduplicationStage deduplicationStage,
            CredibilityCheckStage credibilityCheckStage,
            EditorialScoreStage editorialScoreStage,
            PersonaAlignmentStage personaAlignmentStage,
            PublishDecisionStage publishDecisionStage,
            WritingStage writingStage,
            SelfCritiqueStage selfCritiqueStage,
            MemoryWriteStage memoryWriteStage,
            AgentRepository agentRepository,
            TopicCandidateRepository topicCandidateRepository,
            PipelineMetricsCollector metricsCollector) {
        this.discoveryStage = discoveryStage;
        this.normalizationStage = normalizationStage;
        this.deduplicationStage = deduplicationStage;
        this.credibilityCheckStage = credibilityCheckStage;
        this.editorialScoreStage = editorialScoreStage;
        this.personaAlignmentStage = personaAlignmentStage;
        this.publishDecisionStage = publishDecisionStage;
        this.writingStage = writingStage;
        this.selfCritiqueStage = selfCritiqueStage;
        this.memoryWriteStage = memoryWriteStage;
        this.agentRepository = agentRepository;
        this.topicCandidateRepository = topicCandidateRepository;
        this.metricsCollector = metricsCollector;
    }

    /**
     * Runs one complete pipeline tick for the given agent.
     * Returns the list of published Posts from this tick (may be empty).
     */
    @Transactional
    public List<Post> runTick(Agent agent) {
        UUID tickId = UUID.randomUUID();
        Instant tickStart = Instant.now();

        log.info("=== TICK START [agent={} tick={}] ===", agent.getId(), tickId);

        PipelineMetricsCollector.TickMetrics metrics = metricsCollector.startTick(agent.getId(), tickId);

        try {
            List<Post> published = new ArrayList<>();

            // Stage 0: Resume QUEUED candidates from previous failed ticks
            List<NormalizedCandidate> resumed = resumeQueuedCandidates(agent.getId(), tickId);
            int resumedCount = resumed.size();

            // Stage 1: Discovery
            List<RawCandidate> raw = discoveryStage.discover();
            int discovered = raw.size();
            metrics.recordDiscovery(discovered + resumedCount);

            // Stage 2: Normalization
            List<NormalizedCandidate> normalized = normalizationStage.normalize(raw);

            // Combine resumed candidates (already normalized) with newly discovered
            normalized.addAll(resumed);

            // Stage 3: Deduplication
            List<NormalizedCandidate> unique = deduplicationStage.deduplicate(normalized, agent.getId());

            // Stage 4: Credibility check
            List<NormalizedCandidate> credible = credibilityCheckStage.assessCredibility(unique, agent.getId(), tickId);
            int rejectedAtCredibility = (discovered + resumedCount) - credible.size();
            metrics.recordRejected(rejectedAtCredibility);

            // Stage 5: Editorial scoring (LLM per candidate)
            List<ScoredCandidate> scored = editorialScoreStage.score(credible, agent, tickId);

            // Calculate average editorial score
            double avgScore = scored.stream()
                    .mapToInt(ScoredCandidate::getEditorialScore)
                    .average()
                    .orElse(0.0);
            metrics.recordAvgEditorialScore(avgScore);

            // Stage 6: Persona alignment filter (rule-based)
            List<ScoredCandidate> aligned = personaAlignmentStage.filter(scored, agent, tickId);

            // Count editorial rejections (scored - aligned)
            int editorialRejections = scored.size() - aligned.size();
            metrics.incrementRejected(editorialRejections);

            // Stage 7: Publish decision (exactly one winner)
            PublishDecision publishDecision = publishDecisionStage.decide(aligned);

            // Stage 8: Writing (LLM for the winner only)
            List<DraftPost> drafts = writingStage.write(publishDecision, agent);

            // Stage 9: Self-critique (LLM: critique + rewrite loop with fallback)
            List<DraftPost> approved = selfCritiqueStage.review(drafts, publishDecision, agent, tickId);

            // Stage 10: Memory write
            List<Post> result = memoryWriteStage.persist(approved, agent, tickId);
            published.addAll(result);

            // Update agent timestamps
            Instant now = Instant.now();
            agent.setLastTickAt(now);
            agentRepository.save(agent);

            metrics.recordAccepted(published.size());
            if (!published.isEmpty()) {
                metrics.recordResultedPostId(published.get(0).getId());
            }
            metrics.complete(published.isEmpty() ? null : published.get(0).getId());

            log.info("=== TICK COMPLETE [agent={} tick={}] discovered={} resumed={} published={} ===",
                    agent.getId(), tickId, discovered, resumedCount, published.size());

            return published;

        } catch (Exception e) {
            log.error("=== TICK FAILED [agent={} tick={}]: {} ===", agent.getId(), tickId, e.getMessage(), e);
            metrics.fail();
            return List.of();
        }
    }

    /**
     * Loads QUEUED candidates from previous failed ticks and converts them to NormalizedCandidate
     * so they can resume processing in the current tick.
     */
    private List<NormalizedCandidate> resumeQueuedCandidates(UUID agentId, UUID currentTickId) {
        List<TopicCandidate> queued = topicCandidateRepository.findByAgentIdAndDecision(agentId, "QUEUED");
        if (queued.isEmpty()) {
            return List.of();
        }

        log.info("Resuming {} QUEUED candidates from previous ticks", queued.size());
        List<NormalizedCandidate> resumed = new ArrayList<>();

        for (TopicCandidate tc : queued) {
            try {
                NormalizedCandidate nc = new NormalizedCandidate(
                        tc.getSource(),
                        tc.getRawTitle(),
                        tc.getRawTitle(),
                        tc.getRawUrl(),
                        Instant.now(),
                        generateTopicKey(tc.getRawTitle())
                );
                nc.setCredibilityTier(tc.getCredibilityTier() != null ? tc.getCredibilityTier() : "B");
                nc.setPossibleFollowup(false);
                resumed.add(nc);
            } catch (Exception e) {
                log.warn("Failed to resume QUEUED candidate '{}': {}", tc.getRawTitle(), e.getMessage());
            }
        }

        log.info("Resumed {} QUEUED candidates into current tick", resumed.size());
        return resumed;
    }

    private String generateTopicKey(String title) {
        if (title == null || title.isBlank()) {
            return "unknown-topic";
        }
        String lower = title.toLowerCase();
        String clean = lower.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        String[] words = clean.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.length() > 2) {
                if (sb.length() > 0) sb.append("-");
                sb.append(word);
            }
        }
        String key = sb.length() > 80 ? sb.substring(0, 80) : sb.toString();
        return key.isEmpty() ? "unknown-topic" : key;
    }
}
