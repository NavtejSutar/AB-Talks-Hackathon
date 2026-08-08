package com.wren.agent.domain.repository;

import com.wren.agent.domain.entity.MemoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, UUID> {

    List<MemoryEntry> findByAgentIdAndTopicKey(UUID agentId, String topicKey);

    List<MemoryEntry> findByAgentIdOrderByCreatedAtDesc(UUID agentId);
}
