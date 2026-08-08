package com.wren.agent.domain.repository;

import com.wren.agent.domain.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByStatus(String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Agent a SET a.postSequence = a.postSequence + 1 WHERE a.id = :agentId")
    int incrementPostSequence(@Param("agentId") UUID agentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Agent a SET a.lastTickAt = :lastTickAt WHERE a.id = :agentId")
    int updateLastTickAt(@Param("agentId") UUID agentId, @Param("lastTickAt") Instant lastTickAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Agent a SET a.nextTickAt = :nextTickAt WHERE a.id = :agentId")
    int updateNextTickAt(@Param("agentId") UUID agentId, @Param("nextTickAt") Instant nextTickAt);

    default int incrementPostSequenceAndGet(UUID agentId) {
        incrementPostSequence(agentId);
        return findById(agentId)
                .map(Agent::getPostSequence)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));
    }
}
