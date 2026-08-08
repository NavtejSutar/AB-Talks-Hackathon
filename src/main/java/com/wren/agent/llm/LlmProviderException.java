package com.wren.agent.llm;

public class LlmProviderException extends RuntimeException {

    private final String providerName;

    public LlmProviderException(String providerName, String message) {
        super("[" + providerName + "] " + message);
        this.providerName = providerName;
    }

    public LlmProviderException(String providerName, String message, Throwable cause) {
        super("[" + providerName + "] " + message, cause);
        this.providerName = providerName;
    }

    public String getProviderName() { return providerName; }
}
