package com.wren.agent.memory;

import com.wren.agent.domain.entity.MemoryEntry;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.domain.repository.MemoryEntryRepository;
import com.wren.agent.domain.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MemoryRetrievalService {

    private static final int DEFAULT_RECENT_POSTS = 10;

    private final PostRepository postRepository;
    private final MemoryEntryRepository memoryEntryRepository;

    public MemoryRetrievalService(PostRepository postRepository, MemoryEntryRepository memoryEntryRepository) {
        this.postRepository = postRepository;
        this.memoryEntryRepository = memoryEntryRepository;
    }

    @Transactional(readOnly = true)
    public List<Post> getRecentPosts(UUID agentId, int n) {
        List<Post> all = postRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        return all.stream().limit(n).toList();
    }

    @Transactional(readOnly = true)
    public List<Post> getRecentPosts(UUID agentId) {
        return getRecentPosts(agentId, DEFAULT_RECENT_POSTS);
    }

    @Transactional(readOnly = true)
    public List<MemoryEntry> getRelevantMemory(UUID agentId, String topicKey) {
        return memoryEntryRepository.findByAgentIdAndTopicKey(agentId, topicKey);
    }

    @Transactional(readOnly = true)
    public List<MemoryEntry> getAllMemoryEntries(UUID agentId) {
        return memoryEntryRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    /**
     * Checks if a candidate URL has already been published in a previous post.
     */
    @Transactional(readOnly = true)
    public boolean hasPublishedUrl(UUID agentId, String url) {
        if (url == null || url.isBlank() || agentId == null) {
            return false;
        }
        List<Post> posts = postRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        for (Post p : posts) {
            if (p.getSources() != null) {
                for (String src : p.getSources()) {
                    if (isUrlMatching(url, src)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if any memory entry or post has an exact topic key match.
     */
    @Transactional(readOnly = true)
    public boolean hasSeenTopicKey(UUID agentId, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || agentId == null) {
            return false;
        }
        if (!memoryEntryRepository.findByAgentIdAndTopicKey(agentId, topicKey).isEmpty()) {
            return true;
        }
        return postRepository.findFirstByAgentIdAndTopicKeyOrderByCreatedAtDesc(agentId, topicKey).isPresent();
    }

    /**
     * Fuzzy key match: checks if any existing memory entry or post topic key contains a significant portion of the candidate key.
     */
    @Transactional(readOnly = true)
    public boolean hasFuzzyTopicMatch(UUID agentId, String topicKey) {
        if (topicKey == null || topicKey.isBlank() || agentId == null) {
            return false;
        }

        List<MemoryEntry> entries = memoryEntryRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        List<Post> posts = postRepository.findByAgentIdOrderByCreatedAtDesc(agentId);

        Set<String> existingKeys = new HashSet<>();
        for (MemoryEntry e : entries) {
            if (e.getTopicKey() != null) existingKeys.add(e.getTopicKey());
        }
        for (Post p : posts) {
            if (p.getTopicKey() != null) existingKeys.add(p.getTopicKey());
        }

        String[] keyParts = topicKey.split("-");
        List<String> significantParts = new ArrayList<>();
        for (String part : keyParts) {
            if (part.length() > 3) {
                significantParts.add(part);
            }
        }

        if (significantParts.isEmpty()) return false;

        int matchThreshold = Math.max(1, (int) Math.ceil(significantParts.size() * 0.5));

        for (String existingKey : existingKeys) {
            int matches = 0;
            for (String part : significantParts) {
                if (existingKey.contains(part)) {
                    matches++;
                }
            }
            if (matches >= matchThreshold) return true;
        }
        return false;
    }

    private boolean isUrlMatching(String u1, String u2) {
        if (u1 == null || u2 == null) return false;
        String clean1 = u1.replaceAll("^https?://", "").replaceAll("/$", "");
        String clean2 = u2.replaceAll("^https?://", "").replaceAll("/$", "");
        return clean1.equalsIgnoreCase(clean2);
    }
}
