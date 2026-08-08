package com.wren.agent.pipeline.stages;

import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.entity.MemoryEntry;
import com.wren.agent.domain.entity.Post;
import com.wren.agent.domain.entity.TopicCandidate;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.domain.repository.MemoryEntryRepository;
import com.wren.agent.domain.repository.PostRepository;
import com.wren.agent.domain.repository.TopicCandidateRepository;
import com.wren.agent.pipeline.model.DraftPost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class MemoryWriteStage {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteStage.class);

    private final PostRepository postRepository;
    private final MemoryEntryRepository memoryEntryRepository;
    private final AgentRepository agentRepository;
    private final TopicCandidateRepository topicCandidateRepository;

    public MemoryWriteStage(PostRepository postRepository, MemoryEntryRepository memoryEntryRepository,
                            AgentRepository agentRepository, TopicCandidateRepository topicCandidateRepository) {
        this.postRepository = postRepository;
        this.memoryEntryRepository = memoryEntryRepository;
        this.agentRepository = agentRepository;
        this.topicCandidateRepository = topicCandidateRepository;
    }

    /**
     * Persists approved drafts as Posts and writes corresponding MemoryEntry records.
     * Uses atomic post sequence (p1, p2, ...) from agents.post_sequence.
     * Updates the corresponding topic_candidates entry with resulted_post_id.
     * Returns the list of persisted Posts for API response assembly.
     */
    @Transactional
    public List<Post> persist(List<DraftPost> approvedDrafts, Agent agent, UUID tickId) {
        List<Post> saved = new ArrayList<>();

        for (DraftPost draft : approvedDrafts) {
            try {
                Post post = toPost(draft, agent);
                postRepository.save(post);
                saved.add(post);

                MemoryEntry mem = toMemoryEntry(draft, post, agent);
                memoryEntryRepository.save(mem);

                // Update the corresponding topic_candidate with resulted_post_id
                updateTopicCandidate(draft, post, agent.getId(), tickId);

                log.info("MemoryWriteStage persisted: [Post id={}] '{}'", post.getId(), post.getHeadline());
            } catch (Exception e) {
                log.error("MemoryWriteStage failed to persist draft '{}': {}", draft.getTopic(), e.getMessage(), e);
                throw e; // Re-throw to trigger transaction rollback
            }
        }

        log.info("MemoryWriteStage: {}/{} drafts persisted", saved.size(), approvedDrafts.size());
        return saved;
    }

    private Post toPost(DraftPost draft, Agent agent) {
        // Atomically increment post_sequence and get the new value
        int nextSeq = agentRepository.incrementPostSequenceAndGet(agent.getId());
        String postId = "p" + nextSeq;

        Post post = new Post();
        post.setId(postId);
        post.setAgentId(agent.getId());
        post.setHeadline(draft.getTopic()); // Use topic as headline
        post.setBody(draft.getPost());
        post.setText(draft.getTopic() + "\n\n" + draft.getPost());
        post.setRationale(draft.getRationale());
        post.setSources(draft.getSources());
        post.setHashtags(new ArrayList<>()); // No hashtags in new contract
        post.setTopicKey(draft.getSource().getCandidate().getTopicKey());
        post.setCredibilityTier(draft.getSource().getCredibilityTier());
        post.setEditorialScore((double) draft.getSource().getEditorialScore());
        post.setConfidence((double) draft.getConfidence());
        post.setCreatedAt(Instant.now());
        // is_followup_of will be set if applicable
        if (draft.getSource().getFollowupOfTopicKey() != null) {
            // Find the post with that topic_key
            // For now, leave null - would need a lookup
        }
        return post;
    }

    private MemoryEntry toMemoryEntry(DraftPost draft, Post post, Agent agent) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(UUID.randomUUID());
        entry.setAgentId(agent.getId());
        entry.setTopicKey(draft.getSource().getCandidate().getTopicKey());
        entry.setSummary(draft.getRationale()); // Use rationale as summary
        entry.setPostId(post.getId());
        entry.setCreatedAt(Instant.now());
        // Store Wren's actual stance/opinion from the post
        entry.setOpinionStance(draft.getPost()); // The post content represents the stance
        return entry;
    }

    private void updateTopicCandidate(DraftPost draft, Post post, UUID agentId, UUID tickId) {
        // Find the topic_candidate for this tick and candidate URL
        List<TopicCandidate> candidates = topicCandidateRepository.findByAgentIdAndTickId(agentId, tickId);
        for (TopicCandidate tc : candidates) {
            if (tc.getRawUrl().equals(draft.getSource().getCandidate().getUrl())) {
                tc.setResultedPostId(post.getId());
                topicCandidateRepository.save(tc);
                break;
            }
        }
    }
}