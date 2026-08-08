package com.wren.agent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.pipeline.model.RawCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class GithubDiscoveryAdapter implements DiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(GithubDiscoveryAdapter.class);
    private static final String SOURCE = "github";
    private static final String GITHUB_URL = "https://api.github.com/search/repositories?q=LLM+security+OR+jailbreak+OR+red-teaming+sort:updated&per_page=10";

    @Value("${wren.discovery.github-token:}")
    private String githubToken;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GithubDiscoveryAdapter(ObjectMapper objectMapper) {
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
            log.info("Fetching GitHub entries...");
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Wren-Agent");
            headers.set("Accept", "application/vnd.github.v3+json");

            if (githubToken != null && !githubToken.isBlank()) {
                headers.setBearerAuth(githubToken);
            }

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(GITHUB_URL, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String fullName = item.path("full_name").asText(null);
                        String description = item.path("description").asText(fullName);
                        String htmlUrl = item.path("html_url").asText(null);
                        String updatedAtStr = item.path("updated_at").asText(null);
                        int stars = item.path("stargazers_count").asInt(0);

                        if (fullName != null && htmlUrl != null) {
                            Instant publishedAt = Instant.now();
                            if (updatedAtStr != null && !updatedAtStr.isBlank()) {
                                try {
                                    publishedAt = Instant.parse(updatedAtStr);
                                } catch (Exception ignored) {}
                            }

                            String title = "GitHub: " + fullName;
                            String summary = (description != null ? description : fullName) + " (Stars: " + stars + ")";
                            candidates.add(new RawCandidate(SOURCE, title, summary, htmlUrl, publishedAt));
                        }
                    }
                }
            }
            log.info("Discovered {} candidates from GitHub", candidates.size());

        } catch (Exception e) {
            log.warn("Failed to fetch candidates from GitHub: {}", e.getMessage());
        }

        return candidates;
    }
}
