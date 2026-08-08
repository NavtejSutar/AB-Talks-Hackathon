package com.wren.agent.scheduler;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.pipeline.PipelineOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class AgentTickJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AgentTickJob.class);

    private final UUID agentId;
    private final PipelineOrchestrator orchestrator;
    private final AgentRepository agentRepository;
    private final TickLockManager lockManager;
    private final SchedulerRegistrar schedulerRegistrar;
    private final int serverPort;
    private final RestTemplate restTemplate;

    public AgentTickJob(
            UUID agentId,
            PipelineOrchestrator orchestrator,
            AgentRepository agentRepository,
            TickLockManager lockManager,
            SchedulerRegistrar schedulerRegistrar,
            int serverPort) {
        this.agentId = agentId;
        this.orchestrator = orchestrator;
        this.agentRepository = agentRepository;
        this.lockManager = lockManager;
        this.schedulerRegistrar = schedulerRegistrar;
        this.serverPort = serverPort;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public void run() {
        log.info("AgentTickJob triggered for agent {}", agentId);

        if (!lockManager.tryAcquire(agentId)) {
            log.warn("Agent {} tick skipped — lock acquisition failed (tick in progress)", agentId);
            return;
        }

        try {
            Optional<Agent> agentOpt = agentRepository.findById(agentId);
            if (agentOpt.isEmpty()) {
                log.warn("Agent {} not found in database; stopping scheduled ticks", agentId);
                return;
            }

            Agent agent = agentOpt.get();
            if (!"ACTIVE".equals(agent.getStatus())) {
                log.info("Agent {} status is {}; stopping scheduled ticks", agentId, agent.getStatus());
                return;
            }

            log.info("Executing autonomous tick for agent {} ({})", agentId, agent.getPersonaName());
            orchestrator.runTick(agent);

            // Self-ping fallback post-tick (second-layer defense against dyno sleep)
            selfPing();

        } catch (Exception e) {
            log.error("Error executing tick for agent {}: {}", agentId, e.getMessage(), e);
        } finally {
            lockManager.release(agentId);
            // Schedule the subsequent tick
            schedulerRegistrar.scheduleNextTick(agentId);
        }
    }

    private void selfPing() {
        try {
            String url = "http://localhost:" + serverPort + "/health";
            String response = restTemplate.getForObject(url, String.class);
            log.info("AgentTickJob: self-ping OK -> {}", response);
        } catch (Exception e) {
            log.warn("AgentTickJob: self-ping failed (non-fatal): {}", e.getMessage());
        }
    }
}
