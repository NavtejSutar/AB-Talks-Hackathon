package com.wren.agent.api.controller;

import com.wren.agent.api.dto.CandidateDebugItem;
import com.wren.agent.api.dto.MetricsResponse;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.PipelineMetricsRecord;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.PipelineMetricsRepository;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.exception.AgentNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.wren.agent.pipeline.PipelineOrchestrator;
import com.wren.agent.domain.entity.Post;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class DebugController {

    private final AgentRepository agentRepository;
    private final PipelineMetricsRepository metricsRepository;
    private final TopicCandidateRepository topicCandidateRepository;
    private final PipelineOrchestrator orchestrator;

    public DebugController(
            AgentRepository agentRepository,
            PipelineMetricsRepository metricsRepository,
            TopicCandidateRepository topicCandidateRepository,
            PipelineOrchestrator orchestrator) {
        this.agentRepository = agentRepository;
        this.metricsRepository = metricsRepository;
        this.topicCandidateRepository = topicCandidateRepository;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/tick")
    public ResponseEntity<List<Post>> triggerTick(@RequestParam("agentId") UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AgentNotFoundException("Agent with ID " + agentId + " not found"));
        List<Post> published = orchestrator.runTick(agent);
        return ResponseEntity.ok(published);
    }

    @GetMapping("/metrics")
    public ResponseEntity<MetricsResponse> getMetrics(@RequestParam("agentId") UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AgentNotFoundException("Agent with ID " + agentId + " not found"));

        List<PipelineMetricsRecord> metricsRecords = metricsRepository.findByAgentIdOrderByTickStartedAtDesc(agentId);

        int totalTicks = metricsRecords.size();
        int candidatesDiscovered = metricsRecords.stream().mapToInt(r -> r.getCandidatesDiscovered() != null ? r.getCandidatesDiscovered() : 0).sum();
        int candidatesRejected = metricsRecords.stream().mapToInt(r -> r.getCandidatesRejected() != null ? r.getCandidatesRejected() : 0).sum();
        int candidatesAccepted = metricsRecords.stream().mapToInt(r -> r.getCandidatesAccepted() != null ? r.getCandidatesAccepted() : 0).sum();
        double avgEditorialScore = metricsRecords.stream()
                .filter(r -> r.getAvgEditorialScore() != null)
                .mapToDouble(PipelineMetricsRecord::getAvgEditorialScore)
                .average()
                .orElse(0.0);
        int totalFailovers = metricsRecords.stream().mapToInt(r -> r.getLlmProviderFailovers() != null ? r.getLlmProviderFailovers() : 0).sum();
        int totalRevisions = metricsRecords.stream().mapToInt(r -> r.getSelfCritiqueRevisions() != null ? r.getSelfCritiqueRevisions() : 0).sum();
        int totalSelfCritiqueRejections = metricsRecords.stream().mapToInt(r -> r.getSelfCritiqueRejections() != null ? r.getSelfCritiqueRejections() : 0).sum();

        MetricsResponse response = new MetricsResponse(
                agentId,
                totalTicks,
                candidatesDiscovered,
                candidatesRejected,
                candidatesAccepted,
                avgEditorialScore,
                totalFailovers,
                totalRevisions,
                totalSelfCritiqueRejections,
                agent.getLastTickAt(),
                agent.getNextTickAt(),
                metricsRecords
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<CandidateDebugItem>> getCandidates(@RequestParam("agentId") UUID agentId) {
        if (!agentRepository.existsById(agentId)) {
            throw new AgentNotFoundException("Agent with ID " + agentId + " not found");
        }

        List<TopicCandidate> candidates = topicCandidateRepository.findByAgentIdOrderByDiscoveredAtDesc(agentId);
        List<CandidateDebugItem> debugItems = candidates.stream()
                .map(CandidateDebugItem::new)
                .collect(Collectors.toList());

        return ResponseEntity.ok(debugItems);
    }
}
