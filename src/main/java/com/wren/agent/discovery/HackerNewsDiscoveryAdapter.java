package com.wren.agent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.pipeline.model.RawCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class HackerNewsDiscoveryAdapter implements DiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(HackerNewsDiscoveryAdapter.class);
    private static final String SOURCE = "hn";
    private static final String HN_URL = "https://hn.algolia.com/api/v1/search_by_date?query=AI+security+OR+jailbreak+OR+prompt+injection+OR+adversarial&tags=story&hitsPerPage=10";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HackerNewsDiscoveryAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public List<RawCandidate> fetchCandidates() {
        List<RawCandidate> candidates = new ArrayList<>();
        try {
            log.info("Fetching Hacker News entries...");
            String jsonStr = restTemplate.getForObject(HN_URL, String.class);
            if (jsonStr == null || jsonStr.isBlank()) {
                return candidates;
            }

            JsonNode root = objectMapper.readTree(jsonStr);
            JsonNode hits = root.path("hits");
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    String title = hit.path("title").asText(null);
                    String url = hit.path("url").asText(null);
                    String objectID = hit.path("objectID").asText(null);
                    String createdAtStr = hit.path("created_at").asText(null);
                    int points = hit.path("points").asInt(0);

                    if (title != null && !title.isBlank()) {
                        if (url == null || url.isBlank()) {
                            url = "https://news.ycombinator.com/item?id=" + objectID;
                        }

                        Instant publishedAt = Instant.now();
                        if (createdAtStr != null && !createdAtStr.isBlank()) {
                            try {
                                publishedAt = Instant.parse(createdAtStr);
                            } catch (Exception ignored) {}
                        }

                        String summary = title + " (Points: " + points + ")";
                        candidates.add(new RawCandidate(SOURCE, title, summary, url, publishedAt));
                    }
                }
            }
            log.info("Discovered {} candidates from Hacker News", candidates.size());

        } catch (Exception e) {
            log.warn("Failed to fetch candidates from Hacker News: {}", e.getMessage());
        }

        return candidates;
    }
}
