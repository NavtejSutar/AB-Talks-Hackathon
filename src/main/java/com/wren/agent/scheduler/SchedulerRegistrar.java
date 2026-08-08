package com.wren.agent.scheduler;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.pipeline.PipelineOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class SchedulerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRegistrar.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final AgentRepository agentRepository;
    private final PipelineOrchestrator orchestrator;
    private final TickLockManager lockManager;
    private final Random random = new Random();
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${wren.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    @Value("${wren.scheduler.min-interval-minutes:45}")
    private int minIntervalMinutes;

    @Value("${wren.scheduler.max-interval-minutes:90}")
    private int maxIntervalMinutes;

    public SchedulerRegistrar(
            ThreadPoolTaskScheduler taskScheduler,
            AgentRepository agentRepository,
            PipelineOrchestrator orchestrator,
            TickLockManager lockManager) {
        this.taskScheduler = taskScheduler;
        this.agentRepository = agentRepository;
        this.orchestrator = orchestrator;
        this.lockManager = lockManager;
    }

    /**
     * Boot-time recovery: resumes autonomous scheduling for all ACTIVE agents in the database.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!schedulerEnabled) {
            log.info("SchedulerRegistrar: Autonomous scheduler is disabled (wren.scheduler.enabled=false)");
            return;
        }

        log.info("SchedulerRegistrar: Resuming ACTIVE agents from database on application boot...");
        List<Agent> activeAgents = agentRepository.findByStatus("ACTIVE");
        log.info("SchedulerRegistrar: Found {} ACTIVE agent(s) to schedule", activeAgents.size());

        for (Agent agent : activeAgents) {
            scheduleAgent(agent);
        }
    }

    /**
     * Registers and schedules an agent for autonomous execution.
     * Called on boot and when a new agent is initialized via POST /api/agent/init.
     */
    public synchronized void scheduleAgent(Agent agent) {
        if (agent == null || agent.getId() == null) {
            log.warn("SchedulerRegistrar: Cannot schedule null agent or agent with null ID");
            return;
        }
        if (!schedulerEnabled) {
            log.info("SchedulerRegistrar: Autonomous scheduler disabled; not scheduling agent {}", agent.getId());
            return;
        }

        // Cancel any existing task for this agent
        cancelScheduledTask(agent.getId());

        Instant now = Instant.now();
        Instant startTime;

        if (agent.getLastTickAt() == null) {
            // First tick for a newly initialized agent: execute immediately (within 1 second)
            startTime = now.plusSeconds(1);
            log.info("SchedulerRegistrar: Agent {} has no prior ticks; scheduling first tick immediately at {}", agent.getId(), startTime);
        } else {
            // Existing agent with prior tick: compute randomized delay (45-90 min target)
            long delayMs = getRandomIntervalMillis();
            startTime = now.plusMillis(delayMs);
            log.info("SchedulerRegistrar: Agent {} next tick scheduled at {} (delay: {} ms)", agent.getId(), startTime, delayMs);
        }

        // Update next_tick_at in database
        agent.setNextTickAt(startTime);
        agentRepository.save(agent);

        AgentTickJob job = new AgentTickJob(
                agent.getId(),
                orchestrator,
                agentRepository,
                lockManager,
                this,
                serverPort
        );

        ScheduledFuture<?> future = taskScheduler.schedule(job, startTime);
        if (future != null) {
            scheduledTasks.put(agent.getId(), future);
        }
    }

    /**
     * Schedules the next recurring tick for an agent after a completed tick.
     */
    public synchronized void scheduleNextTick(UUID agentId) {
        if (agentId == null || !schedulerEnabled) {
            return;
        }

        Optional<Agent> agentOpt = agentRepository.findById(agentId);
        if (agentOpt.isEmpty() || !"ACTIVE".equals(agentOpt.get().getStatus())) {
            log.info("SchedulerRegistrar: Agent {} is not ACTIVE; skipping next tick scheduling", agentId);
            cancelScheduledTask(agentId);
            return;
        }

        Agent agent = agentOpt.get();
        long delayMs = getRandomIntervalMillis();
        Instant nextTickAt = Instant.now().plusMillis(delayMs);

        log.info("SchedulerRegistrar: Scheduling next tick for agent {} at {} (in {} minutes)",
                agentId, nextTickAt, Duration.between(Instant.now(), nextTickAt).toMinutes());

        agent.setNextTickAt(nextTickAt);
        agentRepository.save(agent);

        AgentTickJob job = new AgentTickJob(
                agentId,
                orchestrator,
                agentRepository,
                lockManager,
                this,
                serverPort
        );

        ScheduledFuture<?> future = taskScheduler.schedule(job, nextTickAt);
        if (future != null) {
            scheduledTasks.put(agentId, future);
        }
    }

    /**
     * Computes a randomized interval in milliseconds between minIntervalMinutes and maxIntervalMinutes.
     */
    private long getRandomIntervalMillis() {
        int minMinutes = Math.max(1, minIntervalMinutes);
        int maxMinutes = Math.max(minMinutes, maxIntervalMinutes);
        int diff = maxMinutes - minMinutes;
        int selectedMinutes = minMinutes + (diff > 0 ? random.nextInt(diff + 1) : 0);
        return Duration.ofMinutes(selectedMinutes).toMillis();
    }

    /**
     * Cancels an existing scheduled task if present.
     */
    public void cancelScheduledTask(UUID agentId) {
        if (agentId == null) {
            return;
        }
        ScheduledFuture<?> future = scheduledTasks.remove(agentId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            log.info("SchedulerRegistrar: Cancelled existing scheduled task for agent {}", agentId);
        }
    }
}
