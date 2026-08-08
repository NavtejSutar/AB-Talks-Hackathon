package com.wren.agent.domain.repository;

import com.wren.agent.domain.entity.PipelineMetricsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PipelineMetricsRepository extends JpaRepository<PipelineMetricsRecord, UUID> {

    List<PipelineMetricsRecord> findByAgentIdOrderByTickStartedAtDesc(UUID agentId);

    Optional<PipelineMetricsRecord> findByTickId(UUID tickId);
}
