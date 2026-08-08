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
public class NvdDiscoveryAdapter implements DiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(NvdDiscoveryAdapter.class);
    private static final String SOURCE = "nvd";
    private static final String NVD_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=machine+learning&resultsPerPage=10";

    @Value("${wren.discovery.nvd-key:}")
    private String nvdApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NvdDiscoveryAdapter(ObjectMapper objectMapper) {
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
            log.info("Fetching NVD CVE entries...");
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Wren-Agent");

            if (nvdApiKey != null && !nvdApiKey.isBlank()) {
                headers.set("apiKey", nvdApiKey);
            }

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(NVD_URL, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode vulnerabilities = root.path("vulnerabilities");
                if (vulnerabilities.isArray()) {
                    for (JsonNode vulnWrapper : vulnerabilities) {
                        JsonNode cve = vulnWrapper.path("cve");
                        String cveId = cve.path("id").asText(null);
                        String publishedStr = cve.path("published").asText(null);

                        String description = cveId;
                        JsonNode descriptions = cve.path("descriptions");
                        if (descriptions.isArray() && descriptions.size() > 0) {
                            description = descriptions.get(0).path("value").asText(cveId);
                        }

                        if (cveId != null) {
                            String url = "https://nvd.nist.gov/vuln/detail/" + cveId;
                            Instant publishedAt = Instant.now();
                            if (publishedStr != null && !publishedStr.isBlank()) {
                                try {
                                    publishedAt = Instant.parse(publishedStr);
                                } catch (Exception ignored) {}
                            }

                            candidates.add(new RawCandidate(SOURCE, cveId + ": " + truncate(description, 100), description, url, publishedAt));
                        }
                    }
                }
            }
            log.info("Discovered {} candidates from NVD", candidates.size());

        } catch (Exception e) {
            log.warn("Failed to fetch candidates from NVD: {}", e.getMessage());
        }

        return candidates;
    }

    private String truncate(String input, int maxLen) {
        if (input == null) return "";
        if (input.length() <= maxLen) return input;
        return input.substring(0, maxLen) + "...";
    }
}
