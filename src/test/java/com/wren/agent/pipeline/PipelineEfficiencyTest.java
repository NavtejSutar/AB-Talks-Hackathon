package com.wren.agent.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.llm.GeminiRateLimiter;
import com.wren.agent.llm.LlmProviderRouter;
import com.wren.agent.llm.LlmRequest;
import com.wren.agent.llm.LlmResponse;
import com.wren.agent.llm.json.StructuredJsonParser;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import com.wren.agent.pipeline.model.PublishDecision;
import com.wren.agent.pipeline.model.ScoredCandidate;
import com.wren.agent.pipeline.stages.CheapRelevanceFilter;
import com.wren.agent.pipeline.stages.EditorialScoreStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests covering:
 * - 23 candidates → exactly 1 editorial LLM request
 * - 429 → LLM_UNAVAILABLE (not REJECTED)
 * - circuit opens after N failures
 * - circuit prevents subsequent requests
 * - success resets failure count
 * - Writing not called when no winner
 * - highly relevant Tier-B candidate survives max-10 cap over generic Tier-A
 */
class PipelineEfficiencyTest {

    // ---- GeminiRateLimiter circuit-breaker tests ----

    @Test
    void circuitOpensAfterConfiguredFailures() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(5, 2, 120);

        assertThat(limiter.isCircuitOpen()).isFalse();
        limiter.recordFailure();
        assertThat(limiter.isCircuitOpen()).isFalse();  // threshold is 2; 1 not enough
        limiter.recordFailure();
        assertThat(limiter.isCircuitOpen()).isTrue();   // 2nd failure opens circuit
    }

    @Test
    void circuitPreventsSubsequentRequests() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(5, 1, 120);
        limiter.recordFailure(); // threshold=1 → opens immediately
        assertThat(limiter.isCircuitOpen()).isTrue();
    }

    @Test
    void successResetsFailureCount() {
        GeminiRateLimiter limiter = new GeminiRateLimiter(5, 3, 120);
        limiter.recordFailure();
        limiter.recordFailure();
        assertThat(limiter.isCircuitOpen()).isFalse(); // threshold=3, only 2 so far
        limiter.recordSuccess();
        limiter.recordFailure(); // counter reset to 0 by success, so 1 failure only
        assertThat(limiter.isCircuitOpen()).isFalse();
    }

    @Test
    void circuitAutoResets() throws InterruptedException {
        // Use 0-second open period to test auto-reset immediately
        GeminiRateLimiter limiter = new GeminiRateLimiter(5, 1, 0);
        limiter.recordFailure(); // opens circuit
        // After 0s the circuit should self-reset on next isCircuitOpen() check
        Thread.sleep(10); // tiny sleep to ensure elapsed time > 0
        assertThat(limiter.isCircuitOpen()).isFalse();
    }

    // ---- EditorialScoreStage batch call count ----

    @Test
    void editorialScoreSendsSingleBatchRequest() throws Exception {
        // Setup mocks
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        StructuredJsonParser parser = mock(StructuredJsonParser.class);
        ObjectMapper mapper = new ObjectMapper();
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        GeminiRateLimiter limiter = new GeminiRateLimiter(100, 10, 120); // generous limits for test

        // Build a batch JSON response for 5 candidates
        String batchJson = buildBatchJsonResponse(5);
        LlmResponse llmResponse = new LlmResponse(batchJson, "gemini", 100);
        LlmProviderRouter.RouterResult routerResult = new LlmProviderRouter.RouterResult(llmResponse, 0);

        when(parser.extractJson(any())).thenAnswer(inv -> inv.getArgument(0));
        when(router.complete(any())).thenReturn(routerResult);

        EditorialScoreStage stage = new EditorialScoreStage(router, parser, mapper, repo, limiter);

        Agent agent = buildAgent();
        List<NormalizedCandidate> candidates = buildCandidates(5, "exploit cve adversarial");

        stage.score(candidates, agent, UUID.randomUUID());

        // Exactly 1 LLM call for 5 candidates
        verify(router, times(1)).complete(any(LlmRequest.class));
    }

    @Test
    void editorialScoreWith23CandidatesMakesExactlyOneLlmRequest() throws Exception {
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        StructuredJsonParser parser = mock(StructuredJsonParser.class);
        ObjectMapper mapper = new ObjectMapper();
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        GeminiRateLimiter limiter = new GeminiRateLimiter(100, 10, 120);

        String batchJson = buildBatchJsonResponse(23);
        LlmResponse llmResponse = new LlmResponse(batchJson, "gemini", 100);
        when(parser.extractJson(any())).thenAnswer(inv -> inv.getArgument(0));
        when(router.complete(any())).thenReturn(new LlmProviderRouter.RouterResult(llmResponse, 0));

        EditorialScoreStage stage = new EditorialScoreStage(router, parser, mapper, repo, limiter);
        Agent agent = buildAgent();
        List<NormalizedCandidate> candidates = buildCandidates(23, "llm adversarial exploit");

        stage.score(candidates, agent, UUID.randomUUID());

        verify(router, times(1)).complete(any(LlmRequest.class));
    }

    @Test
    void llmFailureProducesLlmUnavailableNotRejected() throws Exception {
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        StructuredJsonParser parser = mock(StructuredJsonParser.class);
        ObjectMapper mapper = new ObjectMapper();
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        GeminiRateLimiter limiter = new GeminiRateLimiter(100, 10, 120);

        when(router.complete(any())).thenThrow(new RuntimeException("HTTP 429 rate limited"));

        EditorialScoreStage stage = new EditorialScoreStage(router, parser, mapper, repo, limiter);
        Agent agent = buildAgent();
        List<NormalizedCandidate> candidates = buildCandidates(3, "exploit adversarial");

        List<ScoredCandidate> result = stage.score(candidates, agent, UUID.randomUUID());

        // Result should be empty (no passes)
        assertThat(result).isEmpty();

        // Candidates persisted with LLM_UNAVAILABLE, not REJECTED
        ArgumentCaptor<com.wren.agent.domain.entity.TopicCandidate> captor =
                ArgumentCaptor.forClass(com.wren.agent.domain.entity.TopicCandidate.class);
        verify(repo, times(3)).save(captor.capture());
        captor.getAllValues().forEach(tc ->
                assertThat(tc.getDecision()).isEqualTo(EditorialScoreStage.DECISION_LLM_UNAVAILABLE)
        );
    }

    @Test
    void circuitOpenCausesLlmUnavailableWithoutCallingLlm() throws Exception {
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        StructuredJsonParser parser = mock(StructuredJsonParser.class);
        ObjectMapper mapper = new ObjectMapper();
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        GeminiRateLimiter limiter = new GeminiRateLimiter(100, 1, 120);
        limiter.recordFailure(); // open the circuit

        EditorialScoreStage stage = new EditorialScoreStage(router, parser, mapper, repo, limiter);
        Agent agent = buildAgent();
        List<NormalizedCandidate> candidates = buildCandidates(2, "llm exploit");

        List<ScoredCandidate> result = stage.score(candidates, agent, UUID.randomUUID());

        assertThat(result).isEmpty();
        // LLM should NOT have been called
        verify(router, never()).complete(any());
        // Candidates persisted as LLM_UNAVAILABLE
        ArgumentCaptor<com.wren.agent.domain.entity.TopicCandidate> captor =
                ArgumentCaptor.forClass(com.wren.agent.domain.entity.TopicCandidate.class);
        verify(repo, times(2)).save(captor.capture());
        captor.getAllValues().forEach(tc ->
                assertThat(tc.getDecision()).isEqualTo(EditorialScoreStage.DECISION_LLM_UNAVAILABLE)
        );
    }

    // ---- CheapRelevanceFilter ranking tests ----

    @Test
    void highlyRelevantTierBSurvivesOverGenericTierA() {
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        CheapRelevanceFilter filter = new CheapRelevanceFilter(repo);

        // Highly relevant Tier-B GitHub candidate (LLM red-teaming framework)
        NormalizedCandidate tierBHighRelevance = makeCandidateWithSource(
                "github", "B",
                "Open-source red-teaming framework for LLM jailbreak adversarial security exploit",
                "A comprehensive red-team toolkit for testing prompt injection and adversarial attacks on LLM agents"
        );

        // Generic NVD CVE — security vuln in Apache httpd, no AI/ML relevance
        NormalizedCandidate tierALowRelevance = makeCandidateWithSource(
                "nvd", "A",
                "CVE-2025-12345 Apache httpd buffer overflow in mod_rewrite",
                "A buffer overflow vulnerability in Apache httpd mod_rewrite allows remote code execution"
        );

        // Another NVD with ML relevance — should rank well
        NormalizedCandidate tierAHighRelevance = makeCandidateWithSource(
                "nvd", "A",
                "CVE-2025-99999 adversarial attack on ML model classifier security exploit",
                "Critical adversarial attack exploits ML classifier model security flaw"
        );

        List<NormalizedCandidate> input = new ArrayList<>(
                List.of(tierALowRelevance, tierBHighRelevance, tierAHighRelevance));

        // With max=2, the top 2 by rank should be the ML-relevant ones, not the Apache CVE
        // Use reflection to set maxCandidatesForLlm=2
        java.lang.reflect.Field f;
        try {
            f = CheapRelevanceFilter.class.getDeclaredField("maxCandidatesForLlm");
            f.setAccessible(true);
            f.set(filter, 2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<NormalizedCandidate> result = filter.filter(input, UUID.randomUUID(), UUID.randomUUID());

        assertThat(result).hasSize(2);
        // tierALowRelevance (generic Apache httpd CVE) should NOT be in the top 2
        assertThat(result).doesNotContain(tierALowRelevance);
        // Both highly-relevant candidates should survive
        assertThat(result).contains(tierBHighRelevance, tierAHighRelevance);
    }

    @Test
    void genericNvdCveWithNoAiKeywordsIsFiltered() {
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        CheapRelevanceFilter filter = new CheapRelevanceFilter(repo);

        NormalizedCandidate genericCve = makeCandidateWithSource(
                "nvd", "A",
                "CVE-2025-00001 Microsoft Windows kernel privilege escalation",
                "A privilege escalation vulnerability in the Windows kernel allows local attackers to gain SYSTEM privileges"
        );

        List<NormalizedCandidate> result = filter.filter(
                new ArrayList<>(List.of(genericCve)), UUID.randomUUID(), UUID.randomUUID());

        // Generic Windows kernel CVE has no AI/ML keywords — should be filtered out
        assertThat(result).isEmpty();
    }

    @Test
    void writingStageNotCalledWhenNoWinner() {
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        StructuredJsonParser parser = mock(StructuredJsonParser.class);
        ObjectMapper mapper = new ObjectMapper();
        com.wren.agent.memory.MemoryRetrievalService memory = mock(com.wren.agent.memory.MemoryRetrievalService.class);
        GeminiRateLimiter limiter = mock(GeminiRateLimiter.class);

        com.wren.agent.pipeline.stages.WritingStage stage =
                new com.wren.agent.pipeline.stages.WritingStage(router, parser, mapper, memory, limiter);

        PublishDecision decision = new PublishDecision(null, List.of());
        Agent agent = buildAgent();

        List<com.wren.agent.pipeline.model.DraftPost> drafts = stage.write(decision, agent);

        assertThat(drafts).isEmpty();
        verifyNoInteractions(router, limiter);
    }

    @Test
    void selfCritiqueNotCalledWhenNoDraft() {
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        StructuredJsonParser parser = mock(StructuredJsonParser.class);
        ObjectMapper mapper = new ObjectMapper();
        com.wren.agent.pipeline.stages.WritingStage writing = mock(com.wren.agent.pipeline.stages.WritingStage.class);
        TopicCandidateRepository repo = mock(TopicCandidateRepository.class);
        GeminiRateLimiter limiter = mock(GeminiRateLimiter.class);

        com.wren.agent.pipeline.stages.SelfCritiqueStage stage =
                new com.wren.agent.pipeline.stages.SelfCritiqueStage(router, parser, mapper, writing, repo, limiter);

        PublishDecision decision = new PublishDecision(null, List.of());
        Agent agent = buildAgent();

        List<com.wren.agent.pipeline.model.DraftPost> approved = stage.review(List.of(), decision, agent, UUID.randomUUID());

        assertThat(approved).isEmpty();
        verifyNoInteractions(router, limiter);
    }

    // ---- helpers ----

    private Agent buildAgent() {
        Agent agent = new Agent();
        agent.setSystemPrompt("You are Wren, an AI security researcher.");
        return agent;
    }

    private List<NormalizedCandidate> buildCandidates(int count, String titleSuffix) {
        List<NormalizedCandidate> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            NormalizedCandidate c = new NormalizedCandidate(
                    "github", "Candidate " + i + " " + titleSuffix,
                    "Summary about " + titleSuffix, "https://example.com/" + i,
                    Instant.now(), "topic-key-" + i);
            c.setCredibilityTier("B");
            list.add(c);
        }
        return list;
    }

    private NormalizedCandidate makeCandidateWithSource(String source, String tier, String title, String summary) {
        NormalizedCandidate c = new NormalizedCandidate(
                source, title, summary, "https://example.com/test",
                Instant.now(), "test-topic");
        c.setCredibilityTier(tier);
        return c;
    }

    /**
     * Builds a mock batch JSON response for N candidates (all score 80, publish=true, confidence=85).
     */
    private String buildBatchJsonResponse(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append(",");
            sb.append(String.format(
                    "{\"candidateId\":\"c%d\",\"topic\":\"topic-%d\",\"score\":80,\"confidence\":85," +
                    "\"publish\":true,\"is_followup_of_topic_key\":null,\"reason\":\"Relevant security content\"}", i, i));
        }
        sb.append("]");
        return sb.toString();
    }
}
