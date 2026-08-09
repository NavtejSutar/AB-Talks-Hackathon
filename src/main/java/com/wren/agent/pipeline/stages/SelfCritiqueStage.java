package com.wren.agent.pipeline.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.llm.GeminiRateLimiter;
import com.wren.agent.llm.LlmProviderRouter;
import com.wren.agent.llm.LlmRequest;
import com.wren.agent.llm.json.StructuredJsonParser;
import com.wren.agent.pipeline.model.DraftPost;
import com.wren.agent.pipeline.model.PublishDecision;
import com.wren.agent.pipeline.model.ScoredCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SelfCritiqueStage {

    private static final Logger log = LoggerFactory.getLogger(SelfCritiqueStage.class);

    // Max 2 fallback candidates per tick (per user design decision)
    private static final int MAX_FALLBACK_ATTEMPTS = 2;

    private final LlmProviderRouter llmRouter;
    private final StructuredJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final WritingStage writingStage;
    private final TopicCandidateRepository topicCandidateRepository;
    private final GeminiRateLimiter geminiRateLimiter;

    public SelfCritiqueStage(LlmProviderRouter llmRouter, StructuredJsonParser jsonParser,
                             ObjectMapper objectMapper, WritingStage writingStage,
                             TopicCandidateRepository topicCandidateRepository,
                             GeminiRateLimiter geminiRateLimiter) {
        this.llmRouter = llmRouter;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.writingStage = writingStage;
        this.topicCandidateRepository = topicCandidateRepository;
        this.geminiRateLimiter = geminiRateLimiter;
    }

    /**
     * Reviews the draft for the winner. If REJECT, falls back to next-highest candidate
     * from PublishDecision's ranked list (up to MAX_FALLBACK_ATTEMPTS total).
     * Persists critique decisions to topic_candidates.
     */
    @Transactional
    public List<DraftPost> review(List<DraftPost> drafts, PublishDecision publishDecision, Agent agent, UUID tickId) {
        List<DraftPost> approved = new ArrayList<>();

        if (drafts.isEmpty() || !publishDecision.hasWinner()) {
            log.info("SelfCritiqueStage: no drafts to review");
            return approved;
        }

        if (llmRouter.getOrderedProviders().stream().noneMatch(provider -> provider.isAvailable())) {
            log.warn("SelfCritiqueStage: no LLM providers available; approving draft without critique");
            approved.add(drafts.get(0));
            persistDecision(drafts.get(0), agent.getId(), tickId, "PUBLISH", "SELF_CRITIQUE",
                    "Offline approval because no LLM provider was available");
            return approved;
        }

        if (geminiRateLimiter.isCircuitOpen()) {
            log.warn("SelfCritiqueStage: Gemini circuit is OPEN — approving draft without critique");
            approved.add(drafts.get(0));
            return approved;
        }

        // Start with the winner draft
        DraftPost currentDraft = drafts.get(0);
        int fallbackIndex = 1; // Index into ranked candidates for fallbacks

        for (int attempt = 0; attempt <= MAX_FALLBACK_ATTEMPTS; attempt++) {
            try {
                geminiRateLimiter.acquirePermit();
                CritiqueResult result = critique(currentDraft, agent);
                geminiRateLimiter.recordSuccess();
                log.info("SelfCritique attempt {}/{}: verdict={}, issues='{}'",
                        attempt, MAX_FALLBACK_ATTEMPTS, result.verdict, result.issues);

                switch (result.verdict) {
                    case PUBLISH -> {
                        approved.add(currentDraft);
                        persistDecision(currentDraft, agent.getId(), tickId, "PUBLISH", "SELF_CRITIQUE",
                                "Approved for publishing: " + String.join("; ", result.issues));
                        log.info("SelfCritique: PUBLISH verdict for '{}'", currentDraft.getTopic());
                        return approved;
                    }
                    case REVISE -> {
                        persistDecision(currentDraft, agent.getId(), tickId, "REVISE", "SELF_CRITIQUE",
                                "Revision requested: " + String.join("; ", result.issues));
                        if (result.revisedPost != null && !result.revisedPost.isBlank()) {
                            currentDraft = applyRevision(currentDraft, result.revisedPost);
                            log.info("SelfCritique: REVISE applied, revised draft: '{}'", currentDraft.getTopic());
                        } else {
                            // No revised post provided, treat as reject
                            log.warn("SelfCritique: REVISE verdict but no revised_post provided");
                        }
                    }
                    case REJECT -> {
                        persistDecision(currentDraft, agent.getId(), tickId, "REJECTED", "SELF_CRITIQUE",
                                "Rejected: " + String.join("; ", result.issues));
                        log.info("SelfCritique: REJECT verdict for '{}': {}", currentDraft.getTopic(), result.issues);
                        // Fall through to fallback logic
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("SelfCritique: interrupted while acquiring rate-limit permit");
                approved.add(currentDraft);
                return approved;
            } catch (Exception e) {
                geminiRateLimiter.recordFailure();
                log.warn("SelfCritique error on attempt {} for '{}': {}", attempt, currentDraft.getTopic(), e.getMessage());
                // On error: include draft as-is to avoid losing valid content
                approved.add(currentDraft);
                return approved;
            }

            // If we reach here, the draft was REJECTED or REVISE without usable output
            // Try to fall back to the next candidate
            if (fallbackIndex < publishDecision.getRankedCandidates().size()) {
                ScoredCandidate nextCandidate = publishDecision.getRankedCandidates().get(fallbackIndex);
                fallbackIndex++;
                try {
                    log.info("SelfCritique: falling back to next candidate: '{}'", nextCandidate.getCandidate().getTitle());
                    currentDraft = writingStage.write(new PublishDecision(nextCandidate, List.of()), agent).get(0);
                } catch (Exception e) {
                    log.warn("SelfCritique: failed to write fallback candidate: {}", e.getMessage());
                    // Continue to next fallback
                }
            } else {
                log.warn("SelfCritique: exhausted all {} fallback candidates", MAX_FALLBACK_ATTEMPTS);
                break;
            }
        }

        log.warn("SelfCritique: all attempts exhausted, no post approved this tick");
        return approved; // Empty list
    }

    private void persistDecision(DraftPost draft, UUID agentId, UUID tickId,
                                 String decision, String decisionStage, String decisionReason) {
        TopicCandidate tc = new TopicCandidate();
        tc.setId(java.util.UUID.randomUUID());
        tc.setAgentId(agentId);
        tc.setTickId(tickId);
        tc.setSource(draft.getSource().getCandidate().getSource());
        tc.setRawTitle(draft.getSource().getCandidate().getTitle());
        tc.setRawUrl(draft.getSource().getCandidate().getUrl());
        tc.setCredibilityTier(draft.getSource().getCredibilityTier());
        tc.setEditorialScore((double) draft.getSource().getEditorialScore());
        tc.setConfidence((double) draft.getSource().getConfidence());
        tc.setPersonaAlignmentPassed(draft.getSource().isPersonaAligned());
        tc.setDecision(decision);
        tc.setDecisionReason(decisionReason);
        tc.setDecisionStage(decisionStage);
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }

    private CritiqueResult critique(DraftPost draft, Agent agent) throws Exception {
        String prompt = """
                You are a self-critique editor reviewing a draft post from Wren, an AI Security Researcher.
                
                Post to review:
                  topic: %s
                  post: %s
                  rationale: %s
                  sources: %s
                  confidence: %d
                
                Check:
                1. Is it factually supported by the provided source material (no fabricated specifics)?
                2. Is it consistent with Wren's established voice and prior stated opinions?
                3. Is it non-repetitive relative to recent posts?
                4. Is it substantive enough to be worth publishing, or generic filler?
                
                Respond ONLY with valid JSON:
                {
                  "verdict": "PUBLISH" | "REVISE" | "REJECT",
                  "issues": ["specific issue 1", "specific issue 2", ...],
                  "revised_post": "..."   // only if verdict is REVISE, the full revised post text
                }
                """.formatted(
                draft.getTopic(),
                draft.getPost(),
                draft.getRationale(),
                String.join(", ", draft.getSources()),
                draft.getConfidence() != null ? draft.getConfidence() : 0
        );

        LlmRequest request = new LlmRequest(agent.getSystemPrompt(), prompt, 0.2, 2048);
        LlmProviderRouter.RouterResult routerResult = llmRouter.complete(request);
        String raw = routerResult.getResponse().getContent();
        String json = jsonParser.extractJson(raw);
        JsonNode node = objectMapper.readTree(json);

        String verdictStr = node.path("verdict").asText("REJECT");
        CritiqueVerdict verdict;
        try {
            verdict = CritiqueVerdict.valueOf(verdictStr);
        } catch (IllegalArgumentException e) {
            verdict = CritiqueVerdict.REJECT;
        }

        List<String> issues = new ArrayList<>();
        JsonNode issuesNode = node.path("issues");
        if (issuesNode.isArray()) {
            issuesNode.forEach(i -> issues.add(i.asText()));
        }

        String revisedPost = node.path("revised_post").asText("");

        return new CritiqueResult(verdict, issues, revisedPost);
    }

    private DraftPost applyRevision(DraftPost original, String revisedPostText) {
        // Parse the revised post text - assuming it's the full post content
        // For simplicity, use the revised text as the post body and keep other fields
        return new DraftPost(
                original.getSource(),
                original.getTopic(),
                revisedPostText,
                original.getRationale(),
                original.getSources(),
                original.getConfidence(),
                original.getRawLlmOutput()
        );
    }

    enum CritiqueVerdict {
        PUBLISH, REVISE, REJECT
    }

    private record CritiqueResult(CritiqueVerdict verdict, List<String> issues, String revisedPost) {}
}