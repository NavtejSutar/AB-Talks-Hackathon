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
public class OpenRouterProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProvider.class);
    private static final String PROVIDER_NAME = "openrouter";
    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    @Value("${wren.llm.openrouter-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenRouterProvider(ObjectMapper objectMapper) {
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
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
            }
            messages.add(Map.of("role", "user", "content", request.getUserPrompt()));

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", "meta-llama/llama-3.1-70b-instruct:free");
            payload.put("messages", messages);
            payload.put("temperature", request.getTemperature());
            payload.put("max_tokens", request.getMaxTokens());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("HTTP-Referer", "https://github.com/NavtejSutar/AB-Talks-Hackathon");
            headers.set("X-Title", "Wren AI Security Agent");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.exchange(OPENROUTER_URL, HttpMethod.POST, entity, String.class);

            long latency = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String content = choices.get(0).path("message").path("content").asText();
                    return new LlmResponse(content, PROVIDER_NAME, latency);
                }
                throw new LlmProviderException(PROVIDER_NAME, "Unexpected response format");
            }

            throw new LlmProviderException(PROVIDER_NAME, "HTTP error: " + response.getStatusCode());

        } catch (LlmProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenRouter LLM request failed", e);
            throw new LlmProviderException(PROVIDER_NAME, "Request failed: " + e.getMessage(), e);
        }
    }
}
