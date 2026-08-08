package com.wren.agent.pipeline.stages;

import com.wren.agent.memory.TopicKeyGenerator;
import com.wren.agent.pipeline.model.NormalizedCandidate;
import com.wren.agent.pipeline.model.RawCandidate;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class NormalizationStage {

    private final TopicKeyGenerator topicKeyGenerator;

    public NormalizationStage(TopicKeyGenerator topicKeyGenerator) {
        this.topicKeyGenerator = topicKeyGenerator;
    }

    public List<NormalizedCandidate> normalize(List<RawCandidate> rawCandidates) {
        List<NormalizedCandidate> normalized = new ArrayList<>();

        for (RawCandidate raw : rawCandidates) {
            if (raw == null) continue;

            String title = clean(raw.getRawTitle());
            String summary = clean(raw.getRawSummary());
            String url = raw.getRawUrl() != null ? raw.getRawUrl().trim() : "";

            if (title.isBlank() || url.isBlank()) continue;

            // Cap summary to avoid huge LLM context
            if (summary.length() > 600) {
                summary = summary.substring(0, 597) + "...";
            }

            String topicKey = topicKeyGenerator.generate(title);

            normalized.add(new NormalizedCandidate(
                    raw.getSource(),
                    title,
                    summary,
                    url,
                    raw.getPublishedAt(),
                    topicKey
            ));
        }

        return normalized;
    }

    private String clean(String input) {
        if (input == null || input.isBlank()) return "";
        // Strip HTML tags with Jsoup
        String stripped = Jsoup.parse(input).text();
        // Collapse whitespace
        return stripped.replaceAll("\\s+", " ").trim();
    }
}
