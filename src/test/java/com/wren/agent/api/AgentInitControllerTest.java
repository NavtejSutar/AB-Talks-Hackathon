package com.wren.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.api.controller.AgentInitController;
import com.wren.agent.api.dto.InitRequest;
import com.wren.agent.api.dto.InitResponse;
import com.wren.agent.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentInitController.class)
public class AgentInitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentService agentService;

    @Test
    public void testInitSuccess() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(agentService.initializeAgent(any())).thenReturn(new InitResponse(agentId.toString()));

        InitRequest request = new InitRequest(new InitRequest.PersonaDto("Ada", "AI Security"));

        mockMvc.perform(post("/api/agent/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()));
    }

    @Test
    public void testInitMissingPersonaNameReturns400() throws Exception {
        InitRequest request = new InitRequest(new InitRequest.PersonaDto("", "AI Security"));

        mockMvc.perform(post("/api/agent/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
