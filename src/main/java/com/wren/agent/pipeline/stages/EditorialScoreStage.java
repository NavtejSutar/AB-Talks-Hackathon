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

import java.util.*;

/**
 * Batch editorial scoring stage — sends ALL candidates in ONE LLM call.
 *
 * <p>Instead of N individual requests (one per candidate), this stage:
 * <ol>
 *   <li>Assigns each candidate a stable short ID (c1..cN).</li>
 *   <li>Sends a single prompt containing all candidates.</li>
 *   <li>Parses the returned JSON array and maps scores back by ID.</li>
 *   <li>If the LLM is unavailable (circuit open, 429 exhausted, 503, timeout),
 *       marks ALL candidates {@code LLM_UNAVAILABLE} instead of {@code REJECTED}
 *       so they can be resumed in the next tick.</li>
 * </ol>
 */
@Component
public class EditorialScoreStage {

    private static final Logger log = LoggerFactory.getLogger(EditorialScoreStage.class);
    private static final int CONFIDENCE_THRESHOLD = 70;

    /** Decision value used when the LLM was unreachable — distinct from editorial REJECTED. */
    public static final String DECISION_LLM_UNAVAILABLE = "LLM_UNAVAILABLE";

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
     * Scores all candidates in a SINGLE batch LLM call.
     * Returns only those that passed: publish==true AND confidence >= 70.
     * Persists EVERY decision to topic_candidates.
     */
    @Transactional
    public List<ScoredCandidate> score(List<NormalizedCandidate> candidates, Agent agent, UUID tickId) {
        if (candidates.isEmpty()) {
            log.info("EditorialScoreStage: no candidates to score");
            return List.of();
        }

        // Assign stable short IDs: c1, c2, ... cN (simpler than UUIDs in LLM prompts)
        Map<String, NormalizedCandidate> idToCandidate = new LinkedHashMap<>();
        int seq = 1;
        for (NormalizedCandidate c : candidates) {
            idToCandidate.put("c" + seq++, c);
        }

        log.info("EditorialScoreStage: scoring {} candidates in one batch LLM call", candidates.size());

        // Acquire a single rate-limit permit for the whole batch
        try {
            geminiRateLimiter.acquirePermit();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("EditorialScoreStage: interrupted while acquiring rate-limit permit; marking all LLM_UNAVAILABLE");
            markAllUnavailable(idToCandidate, agent, tickId, "Rate limiter interrupted");
            return List.of();
        }

        // Check if circuit is open before even attempting the call
        if (geminiRateLimiter.isCircuitOpen()) {
            log.warn("EditorialScoreStage: Gemini circuit is OPEN — skipping batch, marking {} candidates LLM_UNAVAILABLE",
                    candidates.size());
            markAllUnavailable(idToCandidate, agent, tickId, "Gemini circuit breaker open");
            return List.of();
        }

        // Build and execute the single batch call
        String prompt = buildBatchPrompt(idToCandidate, agent);
        LlmRequest request = new LlmRequest(agent.getSystemPrompt(), prompt, 0.2, 4096);

        String rawResponse;
        try {
            LlmProviderRouter.RouterResult result = llmRouter.complete(request);
            rawResponse = result.getResponse().getContent();
            geminiRateLimiter.recordSuccess();
            log.info("EditorialScoreStage: batch LLM call succeeded (failovers={})",
                    result.getFailoverCount());
        } catch (Exception e) {
            log.warn("EditorialScoreStage: batch LLM call FAILED — {} candidates marked LLM_UNAVAILABLE. Reason: {}",
                    candidates.size(), e.getMessage());
            geminiRateLimiter.recordFailure();
            markAllUnavailable(idToCandidate, agent, tickId, "LLM call failed: " + e.getMessage());
            return List.of();
        }

        // Parse the JSON array response and map back to candidates
        return parseBatchResponse(rawResponse, idToCandidate, agent, tickId);
    }

    // ----- batch prompt builder -----

    private String buildBatchPrompt(Map<String, NormalizedCandidate> idToCandidate, Agent agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an editorial AI for Wren, an AI Security Researcher.
                Evaluate the following article candidates and score each one.

                Wren's stable interests (score toward these):
                1. Prompt injection & jailbreak techniques/defenses
                2. Adversarial examples & evasion in ML classifiers
                3. AI supply chain (model weights, datasets, MCP servers, package registries)
                4. Security of AI agents with tool/network access
                5. Notable CVEs or incidents touching ML systems
                6. Research papers with a concrete exploit or defense, not just benchmarks

                Scoring rubric:
                - 90-100: Groundbreaking, highly specific, technically rigorous, directly actionable
                - 70-89: Solid, relevant, accurate, interesting to security community
                - 50-69: Broadly related but generic, lacks depth, low immediate impact
                - 0-49: Off-topic, speculative, duplicated, hobby project, not security-relevant

                Bonus +10 if it covers: CVEs, zero-days, novel attack techniques, LLM/AI security.

                CANDIDATES:
                """);

        for (Map.Entry<String, NormalizedCandidate> entry : idToCandidate.entrySet()) {
            NormalizedCandidate c = entry.getValue();
            sb.append(String.format("""
                    [%s]
                      title: %s
                      source: %s
                      summary: %s
                      published_at: %s
                      credibility_tier: %s
                      possible_followup: %s

                    """,
                    entry.getKey(),
                    c.getTitle(),
                    c.getSource(),
                    c.getSummary() != null ? c.getSummary() : "(none)",
                    c.getPublishedAt() != null ? c.getPublishedAt().toString() : "unknown",
                    c.getCredibilityTier(),
                    c.isPossibleFollowup() ? "yes" : "no"));
        }

        sb.append("""
                Respond ONLY with a valid JSON array — one object per candidate, in the same order.
                Each object must have exactly these fields:
                {
                  "candidateId": "<the [cN] id above>",
                  "topic": "<short topic label>",
                  "score": <0-100 integer>,
                  "confidence": <0-100 integer>,
                  "publish": <true|false>,
                  "is_followup_of_topic_key": "<topic_key or null>",
                  "reason": "<short 1-sentence reason>"
                }

                Only set publish=true if score >= 70 AND confidence >= 70.
                If multiple candidates overlap in subject, keep the highest score and set publish=false on the rest.
                DEDUPLICATION / REPEATED TOPICS RULE:
                If a candidate is marked `possible_followup: yes`, set publish=false and score <= 40 UNLESS the article reports a major NEW vulnerability exploit, CVE disclosure, or official security update not previously covered. Do NOT republish the same paper or topic.
                Output ONLY the JSON array. No preamble, no explanation.
                """);

        return sb.toString();
    }

    // ----- response parser -----

    private List<ScoredCandidate> parseBatchResponse(String rawResponse,
                                                      Map<String, NormalizedCandidate> idToCandidate,
                                                      Agent agent, UUID tickId) {
        List<ScoredCandidate> passed = new ArrayList<>();

        try {
            String jsonText = jsonParser.extractJson(rawResponse);
            JsonNode root = objectMapper.readTree(jsonText);

            if (!root.isArray()) {
                log.warn("EditorialScoreStage: LLM returned non-array JSON — marking all LLM_UNAVAILABLE");
                markAllUnavailable(idToCandidate, agent, tickId, "LLM returned non-array response");
                return List.of();
            }

            // Collect results keyed by candidateId
            Map<String, JsonNode> resultById = new HashMap<>();
            for (JsonNode item : root) {
                String cid = item.path("candidateId").asText("");
                if (!cid.isBlank()) {
                    resultById.put(cid, item);
                }
            }

            for (Map.Entry<String, NormalizedCandidate> entry : idToCandidate.entrySet()) {
                String cid = entry.getKey();
                NormalizedCandidate c = entry.getValue();
                JsonNode node = resultById.get(cid);

                if (node == null) {
                    log.warn("EditorialScoreStage: no result for candidate {} '{}' — marking LLM_UNAVAILABLE",
                            cid, c.getTitle());
                    persistDecision(c, agent, tickId, 0, 0, false,
                            "No result in batch response", "EDITORIAL_SCORE",
                            "Missing in LLM batch response", DECISION_LLM_UNAVAILABLE);
                    continue;
                }

                int score = Math.max(0, Math.min(100, node.path("score").asInt(0)));
                int confidence = Math.max(0, Math.min(100, node.path("confidence").asInt(0)));
                boolean publish = node.path("publish").asBoolean(false);
                String topic = node.path("topic").asText("");
                String followupKey = node.path("is_followup_of_topic_key").asText(null);
                String reason = node.path("reason").asText("no rationale");

                // Gate: must have publish=true AND confidence >= threshold
                boolean passes = publish && confidence >= CONFIDENCE_THRESHOLD;
                String decision;
                String decisionReason;
                if (passes) {
                    decision = "ACCEPTED";
                    decisionReason = reason;
                    passed.add(new ScoredCandidate(c, score, reason, confidence, publish, topic, followupKey));
                    log.info("EditorialScore PASS ({}): [{}] '{}' score={} confidence={}",
                            c.getCredibilityTier(), cid, c.getTitle(), score, confidence);
                } else if (!publish) {
                    decision = "REJECTED";
                    decisionReason = reason + " (publish=false)";
                    log.info("EditorialScore FAIL: [{}] '{}' score={} confidence={} publish=false",
                            cid, c.getTitle(), score, confidence);
                } else {
                    decision = "REJECTED";
                    decisionReason = reason + " (confidence " + confidence + " < " + CONFIDENCE_THRESHOLD + ")";
                    log.info("EditorialScore FAIL: [{}] '{}' score={} confidence={} < threshold",
                            cid, c.getTitle(), score, confidence);
                }

                persistDecision(c, agent, tickId, score, confidence, publish,
                        reason, "EDITORIAL_SCORE", decisionReason, decision);
            }

        } catch (Exception e) {
            log.warn("EditorialScoreStage: failed to parse batch LLM response — marking all LLM_UNAVAILABLE. Error: {}",
                    e.getMessage());
            markAllUnavailable(idToCandidate, agent, tickId, "Failed to parse batch response: " + e.getMessage());
            return List.of();
        }

        log.info("EditorialScoreStage: {}/{} candidates passed confidence gate",
                passed.size(), idToCandidate.size());
        return passed;
    }

    // ----- helpers -----

    private void markAllUnavailable(Map<String, NormalizedCandidate> idToCandidate,
                                    Agent agent, UUID tickId, String reason) {
        for (NormalizedCandidate c : idToCandidate.values()) {
            persistDecision(c, agent, tickId, 0, 0, false,
                    reason, "EDITORIAL_SCORE", reason, DECISION_LLM_UNAVAILABLE);
        }
        log.info("EditorialScoreStage: {} candidates marked {} ({})",
                idToCandidate.size(), DECISION_LLM_UNAVAILABLE, reason);
    }

    private void persistDecision(NormalizedCandidate c, Agent agent, UUID tickId,
                                 int editorialScore, int confidence, boolean publish,
                                 String reason, String decisionStage, String decisionReason,
                                 String decision) {
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
        tc.setPersonaAlignmentPassed(null);
        tc.setDecision(decision);
        tc.setDecisionReason(decisionReason);
        tc.setDecisionStage(decisionStage);
        tc.setResultedPostId(null);
        topicCandidateRepository.save(tc);
    }
}