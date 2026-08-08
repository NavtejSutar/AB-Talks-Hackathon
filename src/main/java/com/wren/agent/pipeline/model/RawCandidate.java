package com.wren.agent.pipeline.model;

import java.time.Instant;

public class RawCandidate {

    private final String source;
    private final String rawTitle;
    private final String rawSummary;
    private final String rawUrl;
    private final Instant publishedAt;

    public RawCandidate(String source, String rawTitle, String rawSummary, String rawUrl, Instant publishedAt) {
        this.source = source;
        this.rawTitle = rawTitle;
        this.rawSummary = rawSummary;
        this.rawUrl = rawUrl;
        this.publishedAt = publishedAt != null ? publishedAt : Instant.now();
    }

    public String getSource() { return source; }
    public String getRawTitle() { return rawTitle; }
    public String getRawSummary() { return rawSummary; }
    public String getRawUrl() { return rawUrl; }
    public Instant getPublishedAt() { return publishedAt; }
}
