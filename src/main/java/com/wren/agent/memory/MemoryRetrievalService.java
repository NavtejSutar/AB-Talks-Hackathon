package com.wren.agent.memory;

import com.wren.agent.domain.entity.MemoryEntry;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.domain.repository.MemoryEntryRepository;
import com.wren.agent.domain.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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
     * Checks if any memory entry has a topic key close to the given key.
     * Used for cross-time deduplication / follow-up detection.
     */
    @Transactional(readOnly = true)
    public boolean hasSeenTopicKey(UUID agentId, String topicKey) {
        return !memoryEntryRepository.findByAgentIdAndTopicKey(agentId, topicKey).isEmpty();
    }

    /**
     * Fuzzy key match: checks if any existing memory entry topic key CONTAINS a significant portion of the candidate key.
     */
    @Transactional(readOnly = true)
    public boolean hasFuzzyTopicMatch(UUID agentId, String topicKey) {
        List<MemoryEntry> entries = memoryEntryRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
        String[] keyParts = topicKey.split("-");
        int matchThreshold = Math.max(1, keyParts.length / 2);

        for (MemoryEntry entry : entries) {
            int matches = 0;
            for (String part : keyParts) {
                if (part.length() > 3 && entry.getTopicKey().contains(part)) {
                    matches++;
                }
            }
            if (matches >= matchThreshold) return true;
        }
        return false;
    }
}
