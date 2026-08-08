package com.wren.agent.api;

import com.wren.agent.api.controller.AgentFeedController;
import com.wren.agent.api.dto.FeedResponse;
import com.wren.agent.api.dto.PostResponseItem;
import com.wren.agent.exception.AgentNotFoundException;
import com.wren.agent.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentFeedController.class)
public class AgentFeedControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeedService feedService;

    @Test
    public void testGetFeedWithPostsSuccess() throws Exception {
        UUID agentId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-08-07T10:30:00Z");

        PostResponseItem post = new PostResponseItem(
                "p1",
                timestamp,
                "Indirect prompt injection attack vector identified in agent tool routing.",
                "Selected because tool safety is critical for autonomous execution.",
                List.of("https://arxiv.org/abs/2401.00000")
        );

        when(feedService.getFeed(agentId)).thenReturn(new FeedResponse(List.of(post)));

        mockMvc.perform(get("/api/agent/feed").param("agentId", agentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.posts[0].id").value("p1"))
                .andExpect(jsonPath("$.posts[0].createdAt").value("2026-08-07T10:30:00Z"))
                .andExpect(jsonPath("$.posts[0].text").value("Indirect prompt injection attack vector identified in agent tool routing."))
                .andExpect(jsonPath("$.posts[0].rationale").value("Selected because tool safety is critical for autonomous execution."))
                .andExpect(jsonPath("$.posts[0].sources[0]").value("https://arxiv.org/abs/2401.00000"));
    }

    @Test
    public void testGetFeedEmptyStateReturnsEmptyArray() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(feedService.getFeed(agentId)).thenReturn(new FeedResponse(Collections.emptyList()));

        mockMvc.perform(get("/api/agent/feed").param("agentId", agentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.posts").isEmpty());
    }

    @Test
    public void testGetFeedUnknownAgentReturns404() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(feedService.getFeed(agentId)).thenThrow(new AgentNotFoundException("Agent not found"));

        mockMvc.perform(get("/api/agent/feed").param("agentId", agentId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Agent not found"));
    }

    @Test
    public void testGetFeedMissingAgentIdReturns400() throws Exception {
        mockMvc.perform(get("/api/agent/feed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
