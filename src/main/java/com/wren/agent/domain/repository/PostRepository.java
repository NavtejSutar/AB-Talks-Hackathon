package com.wren.agent.domain.repository;

import com.wren.agent.domain.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, String> {

    List<Post> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    Optional<Post> findFirstByAgentIdAndTopicKeyOrderByCreatedAtDesc(UUID agentId, String topicKey);
}
