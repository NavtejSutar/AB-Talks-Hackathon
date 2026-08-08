package com.wren.agent.discovery;

import com.wren.agent.pipeline.model.RawCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class ArxivDiscoveryAdapter implements DiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(ArxivDiscoveryAdapter.class);
    private static final String SOURCE = "arxiv";
    private static final String ARXIV_URL = "http://export.arxiv.org/api/query?search_query=cat:cs.CR+OR+cat:cs.AI+OR+cat:cs.LG&sortBy=submittedDate&sortOrder=descending&max_results=10";

    private final RestTemplate restTemplate;

    public ArxivDiscoveryAdapter() {
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
            log.info("Fetching arXiv entries...");
            String xmlResponse = restTemplate.getForObject(ARXIV_URL, String.class);
            if (xmlResponse == null || xmlResponse.isBlank()) {
                return candidates;
            }

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(true);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

            NodeList entries = doc.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String title = getTagValue(entry, "title");
                String summary = getTagValue(entry, "summary");
                String idUrl = getTagValue(entry, "id");
                String publishedStr = getTagValue(entry, "published");

                if (title != null && idUrl != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                    if (summary != null) {
                        summary = summary.replaceAll("\\s+", " ").trim();
                    }

                    Instant publishedAt = Instant.now();
                    if (publishedStr != null && !publishedStr.isBlank()) {
                        try {
                            publishedAt = Instant.parse(publishedStr);
                        } catch (Exception ignored) {}
                    }

                    candidates.add(new RawCandidate(SOURCE, title, summary != null ? summary : title, idUrl, publishedAt));
                }
            }
            log.info("Discovered {} candidates from arXiv", candidates.size());

        } catch (Exception e) {
            log.warn("Failed to fetch candidates from arXiv: {}", e.getMessage());
        }

        return candidates;
    }

    private String getTagValue(Element element, String tagName) {
        NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList != null && nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent();
        }
        return null;
    }
}
