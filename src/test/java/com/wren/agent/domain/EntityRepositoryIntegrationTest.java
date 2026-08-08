package com.wren.agent.domain;

import com.wren.agent.domain.entity.*;
import com.wren.agent.domain.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
public class EntityRepositoryIntegrationTest {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private TopicCandidateRepository topicCandidateRepository;

    @Autowired
    private MemoryEntryRepository memoryEntryRepository;

    @Autowired
    private PipelineMetricsRepository pipelineMetricsRepository;

    @Test
    public void testEntityPersistenceAndArrayMapping() {
        // 1. Agent
        Agent agent = new Agent("Wren", "AI Security");
        agent = agentRepository.save(agent);
        assertThat(agent.getId()).isNotNull();

        // 2. Post with sources array mapping
        Post post = new Post();
        post.setId("p1");
        post.setAgentId(agent.getId());
        post.setCreatedAt(Instant.now());
        post.setText("Test post text");
        post.setRationale("Test rationale");
        post.setSources(List.of("https://arxiv.org/abs/1", "https://github.com/test"));
        post.setTopicKey("test-topic");

        post = postRepository.save(post);
        Post reloadedPost = postRepository.findById("p1").orElseThrow();
        assertThat(reloadedPost.getSources()).containsExactly("https://arxiv.org/abs/1", "https://github.com/test");

        // 3. TopicCandidate
        TopicCandidate candidate = new TopicCandidate();
        candidate.setAgentId(agent.getId());
        candidate.setTickId(agent.getId());
        candidate.setSource("arxiv");
        candidate.setRawTitle("Raw Title");
        candidate.setRawUrl("https://arxiv.org/abs/1");
        candidate.setDecision("ACCEPTED");
        candidate.setDecisionReason("High relevance");
        candidate.setDecisionStage("EDITORIAL_SCORE");

        topicCandidateRepository.save(candidate);

        // 4. MemoryEntry
        MemoryEntry memory = new MemoryEntry(agent.getId(), "p1", "test-topic", "Summary", "Stance");
        memoryEntryRepository.save(memory);

        // Assert repositories
        assertThat(postRepository.findByAgentIdOrderByCreatedAtDesc(agent.getId())).hasSize(1);
        assertThat(memoryEntryRepository.findByAgentIdAndTopicKey(agent.getId(), "test-topic")).hasSize(1);
    }
}
