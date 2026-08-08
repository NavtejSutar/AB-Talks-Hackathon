package com.wren.agent.pipeline.model;

import java.time.Instant;

public class NormalizedCandidate {

    private final String source;
    private final String title;
    private final String summary;
    private final String url;
    private final Instant publishedAt;
    private String topicKey;
    private String credibilityTier;      // A | B | C
    private boolean possibleFollowup;    // flagged by DeduplicationStage

    public NormalizedCandidate(String source, String title, String summary, String url, Instant publishedAt, String topicKey) {
        this.source = source;
        this.title = title;
        this.summary = summary;
        this.url = url;
        this.publishedAt = publishedAt;
        this.topicKey = topicKey;
        this.credibilityTier = "B";
        this.possibleFollowup = false;
    }

    public String getSource() { return source; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getUrl() { return url; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
    public String getCredibilityTier() { return credibilityTier; }
    public void setCredibilityTier(String credibilityTier) { this.credibilityTier = credibilityTier; }
    public boolean isPossibleFollowup() { return possibleFollowup; }
    public void setPossibleFollowup(boolean possibleFollowup) { this.possibleFollowup = possibleFollowup; }
}
