package com.wren.agent.api;

import com.wren.agent.api.controller.DebugController;
import com.wren.agent.config.DebugTokenInterceptor;
import com.wren.agent.config.SecurityConfig;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.PipelineMetricsRepository;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebugController.class)
@Import({SecurityConfig.class, DebugTokenInterceptor.class})
public class DebugControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentRepository agentRepository;

    @MockBean
    private PipelineMetricsRepository metricsRepository;

    @MockBean
    private TopicCandidateRepository topicCandidateRepository;

    private final String debugToken = "wren-debug-secret-2026";

    @Test
    public void testMetricsWithoutTokenReturns401() throws Exception {
        UUID agentId = UUID.randomUUID();

        mockMvc.perform(get("/api/agent/metrics")
                        .param("agentId", agentId.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized: Invalid or missing X-Debug-Token"));
    }

    @Test
    public void testMetricsWithWrongTokenReturns401() throws Exception {
        UUID agentId = UUID.randomUUID();

        mockMvc.perform(get("/api/agent/metrics")
                        .param("agentId", agentId.toString())
                        .header("X-Debug-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testMetricsWithValidTokenReturns200() throws Exception {
        UUID agentId = UUID.randomUUID();
        Agent agent = new Agent("Wren", "AI Security");
        agent.setId(agentId);

        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(metricsRepository.findByAgentIdOrderByTickStartedAtDesc(agentId)).thenReturn(List.of());

        mockMvc.perform(get("/api/agent/metrics")
                        .param("agentId", agentId.toString())
                        .header("X-Debug-Token", debugToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.totalTicks").value(0));
    }

    @Test
    public void testCandidatesWithValidTokenReturns200() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID tickId = UUID.randomUUID();

        when(agentRepository.existsById(agentId)).thenReturn(true);

        TopicCandidate candidate = new TopicCandidate();
        candidate.setAgentId(agentId);
        candidate.setTickId(tickId);
        candidate.setSource("arxiv");
        candidate.setRawTitle("Adversarial LLM Jailbreak");
        candidate.setRawUrl("https://arxiv.org/abs/12345");
        candidate.setDecision("ACCEPTED");
        candidate.setDecisionReason("High relevance");
        candidate.setDecisionStage("EDITORIAL_SCORE");

        when(topicCandidateRepository.findByAgentIdOrderByDiscoveredAtDesc(agentId)).thenReturn(List.of(candidate));

        mockMvc.perform(get("/api/agent/candidates")
                        .param("agentId", agentId.toString())
                        .header("X-Debug-Token", debugToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("arxiv"))
                .andExpect(jsonPath("$[0].decision").value("ACCEPTED"));
    }

    @Test
    public void testUnknownAgentReturns404() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/agent/metrics")
                        .param("agentId", agentId.toString())
                        .header("X-Debug-Token", debugToken))
                .andExpect(status().isNotFound());
    }
}
