package com.wren.agent.pipeline;

import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.memory.MemoryRetrievalService;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import com.wren.agent.pipeline.stages.DeduplicationStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class DeduplicationStageTest {

    private MemoryRetrievalService memoryService;
    private TopicCandidateRepository candidateRepo;
    private DeduplicationStage deduplicationStage;

    @BeforeEach
    public void setUp() {
        memoryService = mock(MemoryRetrievalService.class);
        candidateRepo = mock(TopicCandidateRepository.class);
        deduplicationStage = new DeduplicationStage(memoryService, candidateRepo);
    }

    @Test
    public void testIntraTickDuplicateTopicKeyDropped() {
        NormalizedCandidate c1 = new NormalizedCandidate(
                "arxiv", "LLM Security Exploit", "Summary 1", "https://arxiv.org/abs/0001", Instant.now(), "llm-security-exploit"
        );
        NormalizedCandidate c2 = new NormalizedCandidate(
                "hn", "LLM Security Exploit", "Summary 2", "https://news.ycombinator.com/item?id=0001", Instant.now(), "llm-security-exploit"
        );

        List<NormalizedCandidate> result = deduplicationStage.deduplicate(List.of(c1, c2), UUID.randomUUID());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUrl()).isEqualTo("https://arxiv.org/abs/0001");
    }

    @Test
    public void testIntraTickDuplicateUrlDropped() {
        NormalizedCandidate c1 = new NormalizedCandidate(
                "arxiv", "Title One", "Summary 1", "https://arxiv.org/abs/9999", Instant.now(), "title-one"
        );
        NormalizedCandidate c2 = new NormalizedCandidate(
                "hn", "Title Two", "Summary 2", "https://arxiv.org/abs/9999", Instant.now(), "title-two"
        );

        List<NormalizedCandidate> result = deduplicationStage.deduplicate(List.of(c1, c2), UUID.randomUUID());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Title One");
    }

    @Test
    public void testCrossTimePublishedUrlRejected() {
        UUID agentId = UUID.randomUUID();
        NormalizedCandidate c1 = new NormalizedCandidate(
                "arxiv", "New Title", "Summary", "https://arxiv.org/abs/1234", Instant.now(), "new-title"
        );

        when(memoryService.hasPublishedUrl(agentId, "https://arxiv.org/abs/1234")).thenReturn(true);

        List<NormalizedCandidate> result = deduplicationStage.deduplicate(List.of(c1), agentId);

        assertThat(result).isEmpty();
    }

    @Test
    public void testCrossTimeExactTopicKeyRejected() {
        UUID agentId = UUID.randomUUID();
        NormalizedCandidate c1 = new NormalizedCandidate(
                "arxiv", "Adversarial Prompt Injection", "Summary", "https://arxiv.org/abs/5555", Instant.now(), "adversarial-prompt-injection"
        );

        when(memoryService.hasPublishedUrl(agentId, "https://arxiv.org/abs/5555")).thenReturn(false);
        when(memoryService.hasSeenTopicKey(agentId, "adversarial-prompt-injection")).thenReturn(true);

        List<NormalizedCandidate> result = deduplicationStage.deduplicate(List.of(c1), agentId);

        assertThat(result).isEmpty();
    }

    @Test
    public void testCrossTimeFuzzyTopicKeyRejected() {
        UUID agentId = UUID.randomUUID();
        NormalizedCandidate c1 = new NormalizedCandidate(
                "arxiv", "Adversarial Prompt Injection Attacks on Agents", "Summary", "https://arxiv.org/abs/7777", Instant.now(), "adversarial-prompt-injection-attacks-agents"
        );

        when(memoryService.hasPublishedUrl(agentId, "https://arxiv.org/abs/7777")).thenReturn(false);
        when(memoryService.hasSeenTopicKey(agentId, "adversarial-prompt-injection-attacks-agents")).thenReturn(false);
        when(memoryService.hasFuzzyTopicMatch(agentId, "adversarial-prompt-injection-attacks-agents")).thenReturn(true);

        List<NormalizedCandidate> result = deduplicationStage.deduplicate(List.of(c1), agentId);

        assertThat(result).isEmpty();
    }
}
