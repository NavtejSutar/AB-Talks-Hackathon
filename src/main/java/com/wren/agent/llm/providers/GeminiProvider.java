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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Gemini provider with:
 * <ul>
 *   <li>Configurable model ({@code GEMINI_MODEL} env / {@code wren.llm.gemini-model})</li>
 *   <li>Configurable timeouts ({@code GEMINI_CONNECT_TIMEOUT_MS} / {@code GEMINI_READ_TIMEOUT_MS})</li>
 *   <li>429 handling: one retry with Retry-After or exponential backoff, then throw</li>
 *   <li>503 handling: one retry after short delay, then throw</li>
 *   <li>Timeout handling: immediate throw (no retry) — caller handles circuit state</li>
 *   <li>Safe error logging: status + body snippet; API key never logged</li>
 * </ul>
 */
@Component
public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final String PROVIDER_NAME = "gemini";
    private static final String GEMINI_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=";

    // ---- configuration ----
    @Value("${wren.llm.gemini-key:}")
    private String apiKey;

    @Value("${wren.llm.gemini-model:gemini-3.6-flash}")
    private String geminiModel;

    @Value("${wren.llm.gemini-connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${wren.llm.gemini-read-timeout-ms:45000}")
    private int readTimeoutMs;

    // One retry on 429/503, then give up (circuit-breaker in GeminiRateLimiter handles repeated failures)
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_429_DEFAULT_MS = 5_000L;  // fallback if no Retry-After header
    private static final long RETRY_503_MS = 3_000L;

    private final ObjectMapper objectMapper;

    // RestTemplate is built lazily on first call so @Value fields are populated
    private volatile RestTemplate restTemplate;

    public GeminiProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmProviderException {
        if (!isAvailable()) {
            throw new LlmProviderException(PROVIDER_NAME, "API key is missing or unconfigured");
        }

        String url = String.format(GEMINI_URL_TEMPLATE, geminiModel) + apiKey;
        log.debug("GeminiProvider: using model={}", geminiModel);

        Map<String, Object> payload = buildPayload(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        long startTime = System.currentTimeMillis();
        int attempt = 0;

        while (true) {
            attempt++;
            try {
                ResponseEntity<String> response = getRestTemplate().exchange(
                        url, HttpMethod.POST, entity, String.class);
                long latency = System.currentTimeMillis() - startTime;
                return parseSuccess(response, latency);

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    if (attempt > MAX_RETRIES) {
                        log.warn("GeminiProvider: HTTP 429 after {} attempt(s), giving up. model={}",
                                attempt, geminiModel);
                        throw new LlmProviderException(PROVIDER_NAME,
                                "Rate limited (HTTP 429) after " + attempt + " attempt(s); model=" + geminiModel);
                    }
                    long delay = extractRetryAfterMs(e, RETRY_429_DEFAULT_MS);
                    log.warn("GeminiProvider: HTTP 429 rate-limited, attempt {}/{}. Waiting {}ms. model={}",
                            attempt, MAX_RETRIES, delay, geminiModel);
                    sleepQuietly(delay);

                } else {
                    // Non-retryable 4xx
                    String body = safeBodySnippet(e.getResponseBodyAsString());
                    log.warn("GeminiProvider: HTTP {} error. model={}. Body: {}",
                            e.getStatusCode().value(), geminiModel, body);
                    throw new LlmProviderException(PROVIDER_NAME,
                            "HTTP " + e.getStatusCode().value() + ": " + body);
                }

            } catch (HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503) {
                    if (attempt > MAX_RETRIES) {
                        log.warn("GeminiProvider: HTTP 503 after {} attempt(s), giving up. model={}",
                                attempt, geminiModel);
                        throw new LlmProviderException(PROVIDER_NAME,
                                "Service unavailable (HTTP 503) after " + attempt + " attempt(s)");
                    }
                    log.warn("GeminiProvider: HTTP 503 service unavailable, attempt {}/{}. Waiting {}ms. model={}",
                            attempt, MAX_RETRIES, RETRY_503_MS, geminiModel);
                    sleepQuietly(RETRY_503_MS);

                } else {
                    String body = safeBodySnippet(e.getResponseBodyAsString());
                    log.warn("GeminiProvider: HTTP {} server error. model={}. Body: {}",
                            e.getStatusCode().value(), geminiModel, body);
                    throw new LlmProviderException(PROVIDER_NAME,
                            "HTTP " + e.getStatusCode().value() + ": " + body);
                }

            } catch (ResourceAccessException e) {
                // Covers SocketTimeoutException and other I/O failures
                Throwable cause = e.getCause();
                if (cause instanceof SocketTimeoutException) {
                    log.warn("GeminiProvider: read timeout after {}ms. model={}", readTimeoutMs, geminiModel);
                    throw new LlmProviderException(PROVIDER_NAME,
                            "Read timeout after " + readTimeoutMs + "ms (model=" + geminiModel + ")");
                }
                log.warn("GeminiProvider: I/O error. model={}. Cause: {}", geminiModel, e.getMessage());
                throw new LlmProviderException(PROVIDER_NAME, "I/O error: " + e.getMessage(), e);

            } catch (LlmProviderException e) {
                throw e;
            } catch (Exception e) {
                log.error("GeminiProvider: unexpected error. model={}", geminiModel, e);
                throw new LlmProviderException(PROVIDER_NAME, "Unexpected error: " + e.getMessage(), e);
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
                throw new LlmProviderException(PROVIDER_NAME,
                        "Unexpected JSON structure: missing content candidate");
            } catch (LlmProviderException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmProviderException(PROVIDER_NAME,
                        "Failed to parse Gemini response: " + e.getMessage(), e);
            }
        }
        throw new LlmProviderException(PROVIDER_NAME, "HTTP error: " + response.getStatusCode());
    }

    private long extractRetryAfterMs(HttpClientErrorException e, long defaultMs) {
        try {
            String header = e.getResponseHeaders() != null
                    ? e.getResponseHeaders().getFirst("Retry-After") : null;
            if (header != null) {
                return Math.min(Long.parseLong(header.trim()) * 1000L, 60_000L);
            }
        } catch (NumberFormatException ignored) { /* fall through */ }
        return defaultMs;
    }

    private String safeBodySnippet(String body) {
        if (body == null) return "(empty)";
        return body.length() > 250 ? body.substring(0, 250) + "…" : body;
    }

    private void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Lazy RestTemplate construction so @Value fields are injected before use. */
    private RestTemplate getRestTemplate() {
        if (restTemplate == null) {
            synchronized (this) {
                if (restTemplate == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    restTemplate = new RestTemplate(factory);
                    log.info("GeminiProvider: RestTemplate initialised (connect={}ms, read={}ms, model={})",
                            connectTimeoutMs, readTimeoutMs, geminiModel);
                }
            }
        }
        return restTemplate;
    }
}
