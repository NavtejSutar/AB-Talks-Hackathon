package com.wren.agent.llm;

public class LlmRequest {

    private final String systemPrompt;
    private final String userPrompt;
    private final double temperature;
    private final int maxTokens;

    public LlmRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public LlmRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, 0.3, 2048);
    }

    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
}
