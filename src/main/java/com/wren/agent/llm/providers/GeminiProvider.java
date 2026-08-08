package com.wren.agent.llm.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.llm.LlmProvider;
import com.wren.agent.llm.LlmProviderException;
import com.wren.agent.llm.LlmRequest;
import com.wren.agent.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final String PROVIDER_NAME = "gemini";
    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=";

    // ---- Configuration ----
    /** Injected from wren.llm.gemini-key (env: GEMINI_API_KEY) */
    @Value("${wren.llm.gemini-key:}")
    private String apiKey;

    /** Injected from wren.llm.gemini-model (env: GEMINI_MODEL). Default: gemini-2.5-flash */
    @Value("${wren.llm.gemini-model:gemini-2.5-flash}")
    private String geminiModel;

    // ---- 429 retry config ----
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 15_000L;  // 15 s first retry
    private static final long MAX_BACKOFF_MS = 60_000L;      // cap at 60 s

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GeminiProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * Returns the fully-resolved Gemini endpoint URL (without the key portion).
     * Used for logging/diagnostics — safe to print.
     */
    public String getEndpointBase() {
        return String.format(GEMINI_BASE_URL, geminiModel).replace("?key=", "");
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmProviderException {
        if (!isAvailable()) {
            throw new LlmProviderException(PROVIDER_NAME, "API key is missing or unconfigured");
        }

        String url = String.format(GEMINI_BASE_URL, geminiModel) + apiKey;
        log.debug("GeminiProvider: using model={} endpoint={}",
                geminiModel, String.format(GEMINI_BASE_URL, geminiModel));

        Map<String, Object> payload = buildPayload(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        long startTime = System.currentTimeMillis();
        int attempt = 0;
        long backoffMs = INITIAL_BACKOFF_MS;

        while (true) {
            attempt++;
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, String.class);

                long latency = System.currentTimeMillis() - startTime;
                return parseSuccess(response, latency);

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    long retryDelay = extractRetryAfterMs(e, backoffMs);
                    if (attempt > MAX_RETRIES) {
                        log.warn("GeminiProvider: rate-limited (429) after {} attempts — giving up. model={}",
                                attempt, geminiModel);
                        throw new LlmProviderException(PROVIDER_NAME,
                                "Rate limited (HTTP 429) after " + attempt + " attempts; model=" + geminiModel);
                    }
                    log.warn("GeminiProvider: rate-limited (429), attempt {}/{}. Backing off {}ms. model={}",
                            attempt, MAX_RETRIES, retryDelay, geminiModel);
                    sleepQuietly(retryDelay);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                } else {
                    // Non-retryable HTTP error — log status + safe snippet of body (no key)
                    String safeBody = safeBody(e.getResponseBodyAsString());
                    log.warn("GeminiProvider: HTTP {} error. model={}. Body snippet: {}",
                            e.getStatusCode().value(), geminiModel, safeBody);
                    throw new LlmProviderException(PROVIDER_NAME,
                            "HTTP " + e.getStatusCode().value() + " from Gemini API: " + safeBody);
                }

            } catch (LlmProviderException e) {
                throw e;
            } catch (Exception e) {
                log.error("GeminiProvider: unexpected error during request. model={}", geminiModel, e);
                throw new LlmProviderException(PROVIDER_NAME, "Request failed: " + e.getMessage(), e);
            }
        }
    }

    // ----- helpers -----

    private Map<String, Object> buildPayload(LlmRequest request) {
        Map<String, Object> payload = new HashMap<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            payload.put("system_instruction", Map.of(
                    "parts", List.of(Map.of("text", request.getSystemPrompt()))
            ));
        }
        payload.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", request.getUserPrompt())))
        ));
        payload.put("generationConfig", Map.of(
                "temperature", request.getTemperature(),
                "maxOutputTokens", request.getMaxTokens()
        ));
        return payload;
    }

    private LlmResponse parseSuccess(ResponseEntity<String> response, long latency) throws LlmProviderException {
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            try {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode candidates = root.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
                    if (!textNode.isMissingNode()) {
                        return new LlmResponse(textNode.asText(), PROVIDER_NAME, latency);
                    }
                }
                throw new LlmProviderException(PROVIDER_NAME, "Unexpected JSON structure: missing content candidate");
            } catch (LlmProviderException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmProviderException(PROVIDER_NAME, "Failed to parse Gemini response: " + e.getMessage(), e);
            }
        }
        throw new LlmProviderException(PROVIDER_NAME, "HTTP error: " + response.getStatusCode());
    }

    /**
     * Reads the {@code Retry-After} header (seconds) or falls back to {@code backoffMs}.
     * Never returns more than MAX_BACKOFF_MS.
     */
    private long extractRetryAfterMs(HttpClientErrorException e, long backoffMs) {
        try {
            String retryAfter = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("Retry-After")
                    : null;
            if (retryAfter != null) {
                long secs = Long.parseLong(retryAfter.trim());
                return Math.min(secs * 1000L, MAX_BACKOFF_MS);
            }
        } catch (NumberFormatException ignored) { /* fall through to default */ }
        return Math.min(backoffMs, MAX_BACKOFF_MS);
    }

    /** Truncates the response body for safe logging (no risk of key leakage here — body ≠ URL). */
    private String safeBody(String body) {
        if (body == null) return "(empty)";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
