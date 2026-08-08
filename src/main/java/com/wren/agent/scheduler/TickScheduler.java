package com.wren.agent.scheduler;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.pipeline.PipelineOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

@Component
public class TickScheduler {

    private static final Logger log = LoggerFactory.getLogger(TickScheduler.class);

    private final AgentRepository agentRepository;
    private final PipelineOrchestrator orchestrator;
    private final RestTemplate restTemplate;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${wren.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    public TickScheduler(AgentRepository agentRepository, PipelineOrchestrator orchestrator) {
        this.agentRepository = agentRepository;
        this.orchestrator = orchestrator;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Primary tick: fires every 4 hours.
     * Each ACTIVE agent gets one pipeline run.
     * On tick completion, schedules a self-ping to /health (second-layer fallback for Render cold-start wakeup).
     */
    @Scheduled(cron = "${wren.scheduler.cron:0 0 */4 * * *}")
    public void tick() {
        if (!schedulerEnabled) {
            log.info("TickScheduler disabled (wren.scheduler.enabled=false)");
            return;
        }

        log.info("TickScheduler: tick starting at {}", Instant.now());

        List<Agent> activeAgents = agentRepository.findAll().stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .toList();

        log.info("TickScheduler: {} active agents", activeAgents.size());

        for (Agent agent : activeAgents) {
            try {
                orchestrator.runTick(agent);
            } catch (Exception e) {
                log.error("TickScheduler: unhandled failure for agent {}: {}", agent.getId(), e.getMessage(), e);
            }
        }

        log.info("TickScheduler: tick completed at {}", Instant.now());

        // Self-ping fallback (second-layer defence for Render free tier cold start)
        selfPing();
    }

    /**
     * Self-ping: the app calls its own /health endpoint post-tick.
     * This keeps the Render dyno warm and acts as a secondary liveness heartbeat
     * in case the external cron job fails to fire.
     */
    private void selfPing() {
        try {
            String url = "http://localhost:" + serverPort + "/health";
            String response = restTemplate.getForObject(url, String.class);
            log.info("TickScheduler: self-ping OK -> {}", response);
        } catch (Exception e) {
            // Self-ping failure is non-fatal — external cron is the primary mechanism
            log.warn("TickScheduler: self-ping failed (non-fatal): {}", e.getMessage());
        }
    }
}
