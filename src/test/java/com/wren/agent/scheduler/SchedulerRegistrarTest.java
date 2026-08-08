package com.wren.agent.scheduler;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.pipeline.PipelineOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SchedulerRegistrarTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private AgentRepository agentRepository;
    private PipelineOrchestrator orchestrator;
    private TickLockManager lockManager;
    private PlatformTransactionManager transactionManager;
    private SchedulerRegistrar registrar;

    @BeforeEach
    public void setUp() {
        taskScheduler = mock(ThreadPoolTaskScheduler.class);
        agentRepository = mock(AgentRepository.class);
        orchestrator = mock(PipelineOrchestrator.class);
        lockManager = mock(TickLockManager.class);
        transactionManager = mock(PlatformTransactionManager.class);

        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        registrar = new SchedulerRegistrar(taskScheduler, agentRepository, orchestrator, lockManager, transactionManager);
    }

    @Test
    public void testBootTimeResumptionSchedulesActiveAgents() {
        Agent agent1 = new Agent("Wren", "AI Security");
        Agent agent2 = new Agent("Ada", "AI Security");

        when(agentRepository.findByStatus("ACTIVE")).thenReturn(List.of(agent1, agent2));

        registrar.onApplicationReady();

        // Verify taskScheduler was called twice (once per active agent)
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        verify(agentRepository, times(2)).updateNextTickAt(any(UUID.class), any(Instant.class));
    }

    @Test
    public void testScheduleNextTickUpdatesAgentNextTickAt() {
        Agent agent = new Agent("Wren", "AI Security");
        UUID agentId = agent.getId();

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        registrar.scheduleNextTick(agentId);

        verify(agentRepository).updateNextTickAt(eq(agentId), any(Instant.class));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }
}
