package com.wren.agent.domain;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
public class PostPersistenceRegressionTest {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PostRepository postRepository;

    @Test
    public void testUpdateLastTickAtDoesNotResetPostSequence() {
        Agent agent = new Agent("Wren", "AI Security");
        agent = agentRepository.save(agent);
        assertThat(agent.getPostSequence()).isEqualTo(0);

        agentRepository.updateLastTickAt(agent.getId(), Instant.now());

        Agent reloaded = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloaded.getPostSequence()).isEqualTo(0);
        assertThat(reloaded.getLastTickAt()).isNotNull();
    }

    @Test
    public void testUpdateNextTickAtDoesNotResetPostSequence() {
        Agent agent = new Agent("Wren", "AI Security");
        agent = agentRepository.save(agent);
        assertThat(agent.getPostSequence()).isEqualTo(0);

        agentRepository.updateNextTickAt(agent.getId(), Instant.now().plusSeconds(60));

        Agent reloaded = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloaded.getPostSequence()).isEqualTo(0);
        assertThat(reloaded.getNextTickAt()).isNotNull();
    }

    @Test
    public void testTwoConsecutiveTicksGenerateUniquePostIds() {
        Agent agent = new Agent("Wren", "AI Security");
        agent = agentRepository.save(agent);
        UUID agentId = agent.getId();

        int seq1 = agentRepository.incrementPostSequenceAndGet(agentId);
        int seq2 = agentRepository.incrementPostSequenceAndGet(agentId);

        assertThat(seq1).isEqualTo(1);
        assertThat(seq2).isEqualTo(2);

        Post post1 = new Post();
        post1.setId("p" + seq1);
        post1.setAgentId(agentId);
        post1.setCreatedAt(Instant.now());
        post1.setText("TensorFlow CVE-2021-29512 RaggedBincount heap buffer overflow");
        post1.setRationale("First post rationale");
        post1.setSources(List.of("https://nvd.nist.gov/v1/cve/2021-29512"));
        post1.setTopicKey("tensorflow-cve-2021-29512");
        postRepository.save(post1);

        Post post2 = new Post();
        post2.setId("p" + seq2);
        post2.setAgentId(agentId);
        post2.setCreatedAt(Instant.now());
        post2.setText("Elasticsearch CVE-2018-17247 Machine Learning API XXE");
        post2.setRationale("Second post rationale");
        post2.setSources(List.of("https://nvd.nist.gov/v1/cve/2018-17247"));
        post2.setTopicKey("elasticsearch-cve-2018-17247");
        postRepository.save(post2);

        List<Post> allPosts = postRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        assertThat(allPosts).hasSize(2);
        assertThat(allPosts.get(0).getId()).isEqualTo("p2");
        assertThat(allPosts.get(1).getId()).isEqualTo("p1");
        assertThat(allPosts.get(0).getText()).contains("Elasticsearch");
        assertThat(allPosts.get(1).getText()).contains("TensorFlow");

        Agent finalAgent = agentRepository.findById(agentId).orElseThrow();
        assertThat(finalAgent.getPostSequence()).isEqualTo(2);
    }

    @Test
    public void testSavingAgentDoesNotResetPostSequenceWhenOnlyLastTickAtUpdated() {
        Agent agent = new Agent("Wren", "AI Security");
        agent = agentRepository.save(agent);
        agentRepository.incrementPostSequenceAndGet(agent.getId());

        Agent reloaded = agentRepository.findById(agent.getId()).orElseThrow();
        reloaded.setLastTickAt(Instant.now());
        agentRepository.save(reloaded);

        Agent finalAgent = agentRepository.findById(agent.getId()).orElseThrow();
        assertThat(finalAgent.getPostSequence()).isEqualTo(1);
    }
}
