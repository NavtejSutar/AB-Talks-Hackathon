package com.wren.agent.service;

import com.wren.agent.api.dto.FeedResponse;
import com.wren.agent.api.dto.PostResponseItem;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.PostRepository;
import com.wren.agent.exception.AgentNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FeedService {

    private final AgentRepository agentRepository;
    private final PostRepository postRepository;

    public FeedService(AgentRepository agentRepository, PostRepository postRepository) {
        this.agentRepository = agentRepository;
        this.postRepository = postRepository;
    }

    @Transactional(readOnly = true)
    public FeedResponse getFeed(UUID agentId) {
        if (!agentRepository.existsById(agentId)) {
            throw new AgentNotFoundException("Agent with ID " + agentId + " not found");
        }

        List<Post> posts = postRepository.findByAgentIdOrderByCreatedAtDesc(agentId);

        List<PostResponseItem> items = posts.stream()
                .map(p -> new PostResponseItem(
                        p.getId(),
                        p.getCreatedAt(),
                        p.getText(),
                        p.getRationale(),
                        p.getSources()
                ))
                .collect(Collectors.toList());

        return new FeedResponse(items);
    }
}
