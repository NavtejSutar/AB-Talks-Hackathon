package com.wren.agent.pipeline.model;

import java.util.List;

public class DraftPost {

    private final ScoredCandidate source;
    private final String topic;
    private final String post;
    private final String rationale;
    private final List<String> sources;
    private final Integer confidence;
    private final String rawLlmOutput;

    public DraftPost(ScoredCandidate source, String topic, String post, String rationale, List<String> sources, Integer confidence, String rawLlmOutput) {
        this.source = source;
        this.topic = topic;
        this.post = post;
        this.rationale = rationale;
        this.sources = sources != null ? sources : List.of();
        this.confidence = confidence;
        this.rawLlmOutput = rawLlmOutput;
    }

    public ScoredCandidate getSource() { return source; }
    public String getTopic() { return topic; }
    public String getPost() { return post; }
    public String getRationale() { return rationale; }
    public List<String> getSources() { return sources; }
    public Integer getConfidence() { return confidence; }
    public String getRawLlmOutput() { return rawLlmOutput; }

    /** Full content for self-critique review */
    public String fullContent() {
        return topic + "\n\n" + post + "\n\nRationale: " + rationale + "\n\nSources: " + String.join(" ", sources);
    }
}