package com.wren.agent.domain.repository;

import com.wren.agent.domain.entity.TopicCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TopicCandidateRepository extends JpaRepository<TopicCandidate, UUID> {

    List<TopicCandidate> findByAgentIdAndTickId(UUID agentId, UUID tickId);

    List<TopicCandidate> findByAgentIdOrderByDiscoveredAtDesc(UUID agentId);

    List<TopicCandidate> findByAgentIdAndDecision(UUID agentId, String decision);
}
