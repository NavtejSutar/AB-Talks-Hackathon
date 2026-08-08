package com.wren.agent.llm;

public class LlmResponse {

    private final String content;
    private final String providerName;
    private final long latencyMs;

    public LlmResponse(String content, String providerName, long latencyMs) {
        this.content = content;
        this.providerName = providerName;
        this.latencyMs = latencyMs;
    }

    public String getContent() { return content; }
    public String getProviderName() { return providerName; }
    public long getLatencyMs() { return latencyMs; }
}
