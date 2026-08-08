package com.wren.agent.domain.repository;

import com.wren.agent.domain.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByStatus(String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Agent a SET a.postSequence = a.postSequence + 1 WHERE a.id = :agentId")
    int incrementPostSequence(@Param("agentId") UUID agentId);

    default int incrementPostSequenceAndGet(UUID agentId) {
        incrementPostSequence(agentId);
        return findById(agentId)
                .map(Agent::getPostSequence)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));
    }
}
