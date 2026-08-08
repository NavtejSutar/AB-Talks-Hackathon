package com.wren.agent.api;

import com.wren.agent.domain.entity.PipelineMetricsRecord;
import com.wren.agent.domain.repository.PipelineMetricsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class HealthController {

    private final PipelineMetricsRepository metricsRepository;

    public HealthController(PipelineMetricsRepository metricsRepository) {
        this.metricsRepository = metricsRepository;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * Debug endpoint: returns recent pipeline metrics for observability.
     * Useful for verifying tick execution from external tooling.
     */
    @GetMapping("/debug/metrics")
    public ResponseEntity<List<PipelineMetricsRecord>> recentMetrics() {
        List<PipelineMetricsRecord> all = metricsRepository.findAll();
        // Return most recent first, cap at 20
        List<PipelineMetricsRecord> recent = all.stream()
                .sorted((a, b) -> b.getTickStartedAt().compareTo(a.getTickStartedAt()))
                .limit(20)
                .toList();
        return ResponseEntity.ok(recent);
    }
}
