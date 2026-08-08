package com.wren.agent.llm;

public interface LlmProvider {

    String name();

    boolean isAvailable();

    LlmResponse complete(LlmRequest request) throws LlmProviderException;
}
