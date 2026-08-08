package com.wren.agent.api.dto;

public class InitResponse {

    private String agentId;

    public InitResponse() {}

    public InitResponse(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
}
