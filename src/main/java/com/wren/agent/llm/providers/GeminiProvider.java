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
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    private static final String PROVIDER_NAME = "gemini";
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";

    @Value("${wren.llm.gemini-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GeminiProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
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

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmProviderException {
        if (!isAvailable()) {
            throw new LlmProviderException(PROVIDER_NAME, "API key is missing or unconfigured");
        }

        long startTime = System.currentTimeMillis();
        try {
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

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    GEMINI_URL + apiKey,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            long latency = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode candidates = root.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode textNode = candidates.get(0).path("content").path("parts").get(0).path("text");
                    if (!textNode.isMissingNode()) {
                        return new LlmResponse(textNode.asText(), PROVIDER_NAME, latency);
                    }
                }
                throw new LlmProviderException(PROVIDER_NAME, "Unexpected JSON structure: missing content candidate");
            }

            throw new LlmProviderException(PROVIDER_NAME, "HTTP error: " + response.getStatusCode());

        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Gemini LLM request failed", e);
            throw new LlmProviderException(PROVIDER_NAME, "Request failed: " + e.getMessage(), e);
        }
    }
}
