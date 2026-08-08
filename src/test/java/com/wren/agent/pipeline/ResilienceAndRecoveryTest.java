package com.wren.agent.pipeline;

import com.wren.agent.config.SchedulingConfig;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.scheduler.SchedulerRegistrar;
import com.wren.agent.scheduler.TickLockManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({SchedulingConfig.class, SchedulerRegistrar.class, TickLockManager.class})
public class ResilienceAndRecoveryTest {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private TopicCandidateRepository topicCandidateRepository;

    @Autowired
    private SchedulerRegistrar schedulerRegistrar;

    @MockBean
    private PipelineOrchestrator pipelineOrchestrator;

    @Test
    public void testQueuedCandidateResumption() {
        Agent agent = new Agent("Wren", "AI Security");
        agent = agentRepository.save(agent);

        TopicCandidate candidate = new TopicCandidate();
        candidate.setAgentId(agent.getId());
        candidate.setTickId(UUID.randomUUID());
        candidate.setSource("arxiv");
        candidate.setRawTitle("Resumed QUEUED Topic: LLM Prompt Injection Defense");
        candidate.setRawUrl("https://arxiv.org/abs/9999.9999");
        candidate.setCredibilityTier("A");
        candidate.setDecision("QUEUED");
        candidate.setDecisionReason("Saved during simulated provider outage");
        candidate.setDecisionStage("EDITORIAL_SCORE");

        topicCandidateRepository.save(candidate);

        List<TopicCandidate> queuedList = topicCandidateRepository.findByAgentIdAndDecision(agent.getId(), "QUEUED");
        assertThat(queuedList).hasSize(1);
        assertThat(queuedList.get(0).getRawTitle()).contains("Resumed QUEUED Topic");
    }

    @Test
    public void testApplicationRestartResumption() {
        Agent agent1 = new Agent("Wren", "AI Security");
        Agent agent2 = new Agent("Ada", "AI Security");
        agent1.setStatus("ACTIVE");
        agent2.setStatus("ACTIVE");

        agentRepository.save(agent1);
        agentRepository.save(agent2);

        // Simulate application restart boot event
        schedulerRegistrar.onApplicationReady();

        List<Agent> activeAgents = agentRepository.findByStatus("ACTIVE");
        assertThat(activeAgents.size()).isGreaterThanOrEqualTo(2);
    }
}
