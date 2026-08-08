package com.wren.agent.pipeline.model;

import java.time.Instant;

public class ScoredCandidate {

    private final NormalizedCandidate candidate;
    private final int editorialScore;       // 0-100
    private final String scoreRationale;   // LLM explanation (from "reason" field)
    private final int confidence;          // 0-100 from LLM
    private final boolean publish;         // from LLM
    private final String topic;            // from LLM
    private final String followupOfTopicKey; // from LLM
    private boolean personaAligned;        // set by PersonaAlignmentStage
    private String personaAlignRationale;

    public ScoredCandidate(NormalizedCandidate candidate, int editorialScore, String scoreRationale,
                           int confidence, boolean publish, String topic, String followupOfTopicKey) {
        this.candidate = candidate;
        this.editorialScore = editorialScore;
        this.scoreRationale = scoreRationale;
        this.confidence = confidence;
        this.publish = publish;
        this.topic = topic;
        this.followupOfTopicKey = followupOfTopicKey;
        this.personaAligned = false;
    }

    public NormalizedCandidate getCandidate() { return candidate; }
    public int getEditorialScore() { return editorialScore; }
    public String getScoreRationale() { return scoreRationale; }
    public int getConfidence() { return confidence; }
    public boolean isPublish() { return publish; }
    public String getTopic() { return topic; }
    public String getFollowupOfTopicKey() { return followupOfTopicKey; }
    public boolean isPersonaAligned() { return personaAligned; }
    public String getPersonaAlignRationale() { return personaAlignRationale; }
    public void setPersonaAlignRationale(String r) { this.personaAlignRationale = r; }
    public Instant getPublishedAt() { return candidate.getPublishedAt(); }
    public String getCredibilityTier() { return candidate.getCredibilityTier(); }
}