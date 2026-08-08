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
    private final TickLockManager lockManager;
    private final RestTemplate restTemplate;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${wren.scheduler.cron.enabled:false}")
    private boolean fallbackCronEnabled;

    public TickScheduler(AgentRepository agentRepository, PipelineOrchestrator orchestrator, TickLockManager lockManager) {
        this.agentRepository = agentRepository;
        this.orchestrator = orchestrator;
        this.lockManager = lockManager;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Optional fallback tick cron: disabled by default in favor of SchedulerRegistrar's per-agent randomized scheduler.
     */
    @Scheduled(cron = "${wren.scheduler.cron:0 0 */4 * * *}")
    public void tick() {
        if (!fallbackCronEnabled) {
            log.debug("TickScheduler fallback cron is disabled (wren.scheduler.cron.enabled=false)");
            return;
        }

        log.info("TickScheduler: fallback cron tick starting at {}", Instant.now());

        List<Agent> activeAgents = agentRepository.findByStatus("ACTIVE");

        log.info("TickScheduler: {} active agents", activeAgents.size());

        for (Agent agent : activeAgents) {
            if (!lockManager.tryAcquire(agent.getId())) {
                log.warn("TickScheduler: agent {} tick skipped — tick in progress", agent.getId());
                continue;
            }
            try {
                orchestrator.runTick(agent);
            } catch (Exception e) {
                log.error("TickScheduler: unhandled failure for agent {}: {}", agent.getId(), e.getMessage(), e);
            } finally {
                lockManager.release(agent.getId());
            }
        }

        log.info("TickScheduler: tick completed at {}", Instant.now());

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
