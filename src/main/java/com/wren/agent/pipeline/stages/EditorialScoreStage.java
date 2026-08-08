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
import com.wren.agent.pipeline.model.NormalizedCandidate;
import com.wren.agent.pipeline.model.ScoredCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class EditorialScoreStage {

    private static final Logger log = LoggerFactory.getLogger(EditorialScoreStage.class);
    private static final int PUBLISH_THRESHOLD = 70;
    private static final int CONFIDENCE_THRESHOLD = 70;

    private final LlmProviderRouter llmRouter;
    private final StructuredJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final TopicCandidateRepository topicCandidateRepository;
    private final GeminiRateLimiter geminiRateLimiter;

    public EditorialScoreStage(LlmProviderRouter llmRouter, StructuredJsonParser jsonParser,
                               ObjectMapper objectMapper, TopicCandidateRepository topicCandidateRepository,
                               GeminiRateLimiter geminiRateLimiter) {
        this.llmRouter = llmRouter;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.topicCandidateRepository = topicCandidateRepository;
        this.geminiRateLimiter = geminiRateLimiter;
    }

    /**
     * Calls LLM once per candidate to obtain structured editorial judgment.
     * Applies confidence gate: confidence >= 70 AND publish == true.
     * Persists EVERY decision (accepted and rejected) to topic_candidates.
     */
    @Transactional
    public List<ScoredCandidate> score(List<NormalizedCandidate> candidates, Agent agent, UUID tickId) {
        List<ScoredCandidate> scored = new ArrayList<>();

        for (NormalizedCandidate c : candidates) {
            try {
                // Acquire a rate-limit permit before each LLM call so we never
                // exceed wren.llm.gemini-rpm (default 5) requests per minute.
                geminiRateLimiter.acquirePermit();
                ScoredCandidate sc = scoreCandidate(c, agent, tickId);
                if (sc.isPublish() && sc.getConfidence() >= CONFIDENCE_THRESHOLD) {
                    scored.add(sc);
                    log.info("EditorialScore PASS ({}): '{}' score={} confidence={} publish={}",
                            c.getCredibilityTier(), c.getTitle(), sc.getEditorialScore(), sc.getConfidence(), sc.isPublish());
                } else {
                    log.info("EditorialScore FAIL: '{}' score={} confidence={} publish={} (thresholds: score>={}, confidence>={})",
                            c.getTitle(), sc.getEditorialScore(), sc.getConfidence(), sc.isPublish(),
                            PUBLISH_THRESHOLD, CONFIDENCE_THRESHOLD);
                }
            } catch (Exception e) {
                log.warn("EditorialScore failed for '{}': {}", c.getTitle(), e.getMessage());
                // Persist as rejected due to error
                persistDecision(c, agent, tickId, 0, 0, false, "error", "EDITORIAL_SCORE", "LLM call failed: " + e.getMessage());
            }
        }

        log.info("EditorialScoreStage: {}/{} candidates passed confidence gate", scored.size(), candidates.size());
        return scored;
    }

    private ScoredCandidate scoreCandidate(NormalizedCandidate c, Agent agent, UUID tickId) throws Exception {
        String prompt = buildPrompt(c, agent);
        LlmRequest request = new LlmRequest(agent.getSystemPrompt(), prompt);
        LlmProviderRouter.RouterResult routerResult = llmRouter.complete(request);
        String raw = routerResult.getResponse().getContent();
        String json = jsonParser.extractJson(raw);

        JsonNode node = objectMapper.readTree(json);
        String topic = node.path("topic").asText("");
        int score = node.path("score").asInt(0);
        int confidence = node.path("confidence").asInt(0);
        boolean publish = node.path("publish").asBoolean(false);
        String followupOfTopicKey = node.path("is_followup_of_topic_key").asText(null);
        String reason = node.path("reason").asText("no rationale");

        // Clamp values
        score = Math.max(0, Math.min(100, score));
        confidence = Math.max(0, Math.min(100, confidence));

        // Determine decision
        String decision;
        String decisionReason;
        if (publish && confidence >= CONFIDENCE_THRESHOLD) {
            decision = "ACCEPTED";
            decisionReason = reason;
        } else if (!publish) {
            decision = "REJECTED";
            decisionReason = reason + " (publish=false)";
        } else {
            decision = "REJECTED";
            decisionReason = reason + " (confidence " + confidence + " < " + CONFIDENCE_THRESHOLD + ")";
        }

        // Persist decision to topic_candidates
        persistDecision(c, agent, tickId, score, confidence, publish, reason, "EDITORIAL_SCORE", decisionReason, decision);

        return new ScoredCandidate(c, score, reason, confidence, publish, topic, followupOfTopicKey);
    }

    private void persistDecision(NormalizedCandidate c, Agent agent, UUID tickId,
                                 int editorialScore, int confidence, boolean publish,
                                 String reason, String decisionStage, String decisionReason) {
        persistDecision(c, agent, tickId, editorialScore, confidence, publish, reason, decisionStage, decisionReason,
                publish && confidence >= CONFIDENCE_THRESHOLD ? "ACCEPTED" : "REJECTED");
    }

    private void persistDecision(NormalizedCandidate c, Agent agent, UUID tickId,
                                 int editorialScore, int confidence, boolean publish,
                                 String reason, String decisionStage, String decisionReason, String decision) {
        TopicCandidate tc = new TopicCandidate();
        tc.setId(UUID.randomUUID());
        tc.setAgentId(agent.getId());
        tc.setTickId(tickId);
        tc.setSource(c.getSource());
        tc.setRawTitle(c.getTitle());
        tc.setRawUrl(c.getUrl());
        tc.setCredibilityTier(c.getCredibilityTier());
        tc.setEditorialScore((double) editorialScore);
        tc.setConfidence((double) confidence);
        tc.setPersonaAlignmentPassed(null); // Not yet checked
        tc.setDecision(decision);
        tc.setDecisionReason(decisionReason);
        tc.setDecisionStage(decisionStage);
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }

    private String buildPrompt(NormalizedCandidate c, Agent agent) {
        return """
                You are an editorial AI for Wren, an AI Security Researcher.
                Evaluate the following article candidate and provide a structured judgment.
                
                Candidate:
                  title: %s
                  source: %s
                  summary: %s
                  published_at: %s
                  credibility_tier: %s
                  possible_followup: %s
                
                Wren's stable interests:
                1. Prompt injection & jailbreak techniques/defenses
                2. Adversarial examples & evasion in ML classifiers
                3. AI supply chain (model weights, datasets, MCP servers, package registries)
                4. Security of AI agents with tool/network access
                5. Notable CVEs or incidents touching ML systems
                6. Research papers with a concrete exploit or defense, not just benchmarks
                
                Respond ONLY with valid JSON:
                {
                  "topic": "<short topic label>",
                  "score": <0-100 integer>,
                  "confidence": <0-100 integer>,
                  "publish": <true|false>,
                  "is_followup_of_topic_key": "<topic_key or null>",
                  "reason": "<specific, non-generic reason for the decision>"
                }
                
                Scoring rubric:
                - 90-100: Groundbreaking, highly specific, technically rigorous, directly actionable
                - 70-89: Solid, relevant, accurate, interesting to security community
                - 50-69: Broadly related but generic, lacks depth, low immediate impact
                - 0-49: Off-topic, speculative, duplicated, clickbait
                
                Bonus +10 if it covers: CVEs, zero-days, novel attack techniques, LLM/AI security, critical infrastructure threats.
                
                Confidence gate: Only publish if confidence >= 70 AND publish == true.
                If multiple strong candidates overlap in subject, keep the highest score and reject the rest.
                If this is a possible followup, judge if it's a genuine new development vs rehash.
                """.formatted(
                c.getTitle(), c.getSource(), c.getSummary(),
                c.getPublishedAt() != null ? c.getPublishedAt().toString() : "unknown",
                c.getCredibilityTier(),
                c.isPossibleFollowup() ? "yes (prior coverage exists, prefer if significant new development)" : "no"
        );
    }
}