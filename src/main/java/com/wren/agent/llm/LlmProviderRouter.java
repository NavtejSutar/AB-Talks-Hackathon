package com.wren.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class LlmProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRouter.class);

    private final Map<String, LlmProvider> providerMap;

    @Value("${wren.llm.priority:gemini,groq,openrouter,cerebras}")
    private String priorityConfig = "gemini,groq,openrouter,cerebras";

    public LlmProviderRouter(List<LlmProvider> providers) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(
                        p -> p.name().toLowerCase(),
                        p -> p,
                        (existing, replacement) -> existing
                ));
    }

    public RouterResult complete(LlmRequest request) throws LlmProviderException {
        List<LlmProvider> orderedProviders = getOrderedProviders();

        if (orderedProviders.isEmpty()) {
            throw new LlmProviderException("ROUTER", "No LLM providers are available or configured with API keys.");
        }

        int failoverCount = 0;
        List<String> failureMessages = new ArrayList<>();

        for (LlmProvider provider : orderedProviders) {
            if (!provider.isAvailable()) {
                log.debug("Skipping provider {} (not available/configured)", provider.name());
                continue;
            }

            try {
                log.info("Attempting LLM call using provider: {}", provider.name());
                LlmResponse response = provider.complete(request);
                log.info("LLM call succeeded with provider: {} (latency: {} ms)", provider.name(), response.getLatencyMs());
                return new RouterResult(response, failoverCount);

            } catch (Exception e) {
                failoverCount++;
                String msg = provider.name() + " failed: " + e.getMessage();
                log.warn("LLM provider failure. Failover count: {}. Reason: {}", failoverCount, msg);
                failureMessages.add(msg);
            }
        }

        throw new LlmProviderException("ROUTER", "All configured LLM providers failed for request. Failures: " + String.join("; ", failureMessages));
    }

    public List<LlmProvider> getOrderedProviders() {
        List<LlmProvider> result = new ArrayList<>();
        String[] names = priorityConfig.split(",");
        for (String rawName : names) {
            String name = rawName.trim().toLowerCase();
            LlmProvider provider = providerMap.get(name);
            if (provider != null) {
                result.add(provider);
            }
        }
        // Append any extra registered providers not explicitly in priority string
        for (LlmProvider p : providerMap.values()) {
            if (!result.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    public static class RouterResult {
        private final LlmResponse response;
        private final int failoverCount;

        public RouterResult(LlmResponse response, int failoverCount) {
            this.response = response;
            this.failoverCount = failoverCount;
        }

        public LlmResponse getResponse() { return response; }
        public int getFailoverCount() { return failoverCount; }
    }
}
