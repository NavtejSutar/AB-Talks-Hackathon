package com.wren.agent.pipeline.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.llm.GeminiRateLimiter;
import com.wren.agent.llm.LlmProviderRouter;
import com.wren.agent.llm.LlmRequest;
import com.wren.agent.llm.json.StructuredJsonParser;
import com.wren.agent.memory.MemoryRetrievalService;
import com.wren.agent.persona.PersonaProfile;
import com.wren.agent.pipeline.model.DraftPost;
import com.wren.agent.pipeline.model.PublishDecision;
import com.wren.agent.pipeline.model.ScoredCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class WritingStage {

    private static final Logger log = LoggerFactory.getLogger(WritingStage.class);

    private final LlmProviderRouter llmRouter;
    private final StructuredJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final MemoryRetrievalService memoryService;
    private final GeminiRateLimiter geminiRateLimiter;

    public WritingStage(LlmProviderRouter llmRouter, StructuredJsonParser jsonParser,
                        ObjectMapper objectMapper, MemoryRetrievalService memoryService,
                        GeminiRateLimiter geminiRateLimiter) {
        this.llmRouter = llmRouter;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.geminiRateLimiter = geminiRateLimiter;
    }

    public List<DraftPost> write(PublishDecision publishDecision, Agent agent) {
        List<DraftPost> drafts = new ArrayList<>();

        if (!publishDecision.hasWinner()) {
            log.info("WritingStage: no winner to write");
            return drafts;
        }

        if (llmRouter.getOrderedProviders().stream().noneMatch(provider -> provider.isAvailable())) {
            log.warn("WritingStage: no LLM providers available; generating offline fallback draft");
            drafts.add(buildOfflineDraft(publishDecision.getWinner(), agent));
            return drafts;
        }

        if (geminiRateLimiter.isCircuitOpen()) {
            log.warn("WritingStage: Gemini circuit is OPEN — generating offline fallback draft");
            drafts.add(buildOfflineDraft(publishDecision.getWinner(), agent));
            return drafts;
        }

        ScoredCandidate winner = publishDecision.getWinner();
        List<Post> recentPosts = memoryService.getRecentPosts(agent.getId());
        String recentContext = buildRecentContext(recentPosts);

        try {
            geminiRateLimiter.acquirePermit();
            DraftPost draft = writeDraft(winner, agent, recentContext);
            geminiRateLimiter.recordSuccess();
            drafts.add(draft);
            log.info("WritingStage drafted: topic='{}'", draft.getTopic());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("WritingStage: interrupted while acquiring rate-limit permit");
            drafts.add(buildOfflineDraft(winner, agent));
        } catch (Exception e) {
            geminiRateLimiter.recordFailure();
            log.warn("WritingStage failed for '{}': {}", winner.getCandidate().getTitle(), e.getMessage());
            drafts.add(buildOfflineDraft(winner, agent));
        }

        return drafts;
    }

    private DraftPost buildOfflineDraft(ScoredCandidate sc, Agent agent) {
        String topic = deriveTopic(sc);
        String title = sc.getCandidate().getTitle();
        String summary = sc.getCandidate().getSummary() != null ? sc.getCandidate().getSummary() : title;
        String source = sc.getCandidate().getUrl();
        String post = String.format(
                "%s\n\nThis item points to %s and highlights why it matters for agentic and ML security. %s\n\nThe practical question is whether teams are actually handling this class of issue before it gets reused or scaled.",
                title,
                source,
                summary != null && !summary.isBlank() ? summary : "It is relevant because it maps to the agent's security focus.");
        String rationale = "Offline fallback draft generated because no LLM provider was available";
        List<String> sources = new ArrayList<>();
        sources.add(source);
        Integer confidence = Math.max(70, Math.min(95, sc.getEditorialScore()));
        return new DraftPost(sc, topic, post, rationale, sources, confidence, "offline-fallback");
    }

    private String deriveTopic(ScoredCandidate sc) {
        if (sc.getTopic() != null && !sc.getTopic().isBlank()) {
            return sc.getTopic();
        }
        if (sc.getCandidate().getTitle() != null && !sc.getCandidate().getTitle().isBlank()) {
            return sc.getCandidate().getTitle();
        }
        return "Security topic";
    }

    private DraftPost writeDraft(ScoredCandidate sc, Agent agent, String recentContext) throws Exception {
        String prompt = buildPrompt(sc, agent, recentContext);
        LlmRequest request = new LlmRequest(agent.getSystemPrompt(), prompt, 0.3, 2048);
        LlmProviderRouter.RouterResult routerResult = llmRouter.complete(request);
        String raw = routerResult.getResponse().getContent();
        String json = jsonParser.extractJson(raw);

        JsonNode node = objectMapper.readTree(json);
        String topic = node.path("topic").asText("").trim();
        String post = node.path("post").asText("").trim();
        String rationale = node.path("rationale").asText("").trim();
        Integer confidence = node.path("confidence").asInt(0);

        List<String> sources = new ArrayList<>();
        JsonNode sourcesNode = node.path("sources");
        if (sourcesNode.isArray()) {
            sourcesNode.forEach(s -> sources.add(s.asText()));
        }
        // Fallback: use the candidate's URL if no sources provided
        if (sources.isEmpty()) {
            sources.add(sc.getCandidate().getUrl());
        }

        // Clamp confidence
        confidence = Math.max(0, Math.min(100, confidence));

        return new DraftPost(sc, topic, post, rationale, sources, confidence, raw);
    }

    private String buildPrompt(ScoredCandidate sc, Agent agent, String recentContext) {
        String personaName = agent.getName() != null ? agent.getName() : "Wren";
        return """
                You are %s, an AI Security Researcher. Write a short, sharp, technically literate post
                about the following topic. Follow your voice bible exactly.

                Voice bible:
                %s

                Topic to write about:
                  title: %s
                  source: %s
                  summary: %s
                  credibility_tier: %s

                Recent posts context (avoid repeating these angles):
                %s

                Requirements:
                - topic: short topic label (e.g., "Prompt injection in agent tool routing")
                - post: 2-5 short sentences. Precise, slightly dry. One concrete technical detail minimum.
                  No hype, no emoji, no exclamation points. No marketing language.
                  Close with an implication or pointed question — never a generic call-to-action.
                - rationale: Why this topic was selected, why it is relevant now, and what the sources are.
                - sources: array of source URLs (include at least the primary source URL)
                - confidence: 0-100 integer, your confidence in the post's accuracy and relevance

                Respond ONLY with valid JSON:
                {
                  "topic": "<string>",
                  "post": "<string>",
                  "rationale": "<string>",
                  "sources": ["<url1>", "<url2>", ...],
                  "confidence": <0-100 integer>
                }
                """.formatted(
                personaName,
                PersonaProfile.VOICE_BIBLE,
                sc.getCandidate().getTitle(),
                sc.getCandidate().getSource(),
                sc.getCandidate().getSummary(),
                sc.getCredibilityTier(),
                recentContext.isBlank() ? "(no prior posts)" : recentContext
        );
    }

    private String buildRecentContext(List<Post> recentPosts) {
        if (recentPosts == null || recentPosts.isEmpty()) return "";
        return recentPosts.stream()
                .limit(5)
                .map(p -> "- " + (p.getHeadline() != null ? p.getHeadline() : p.getText()))
                .collect(Collectors.joining("\n"));
    }
}