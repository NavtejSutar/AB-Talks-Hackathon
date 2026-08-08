package com.wren.agent.api.controller;

import com.wren.agent.api.dto.InitRequest;
import com.wren.agent.api.dto.InitResponse;
import com.wren.agent.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentInitController {

    private final AgentService agentService;

    public AgentInitController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/init")
    public ResponseEntity<InitResponse> initAgent(@Valid @RequestBody InitRequest request) {
        InitResponse response = agentService.initializeAgent(request);
        return ResponseEntity.ok(response);
    }
}
