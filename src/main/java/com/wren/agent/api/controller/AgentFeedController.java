package com.wren.agent.api.controller;

import com.wren.agent.api.dto.FeedResponse;
import com.wren.agent.service.FeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentFeedController {

    private final FeedService feedService;

    public AgentFeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/feed")
    public ResponseEntity<FeedResponse> getFeed(@RequestParam("agentId") UUID agentId) {
        FeedResponse feed = feedService.getFeed(agentId);
        return ResponseEntity.ok(feed);
    }
}
