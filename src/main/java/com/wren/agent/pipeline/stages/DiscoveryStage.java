package com.wren.agent.pipeline.stages;

import com.wren.agent.discovery.DiscoveryAdapter;
import com.wren.agent.pipeline.model.RawCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DiscoveryStage {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryStage.class);

    private final List<DiscoveryAdapter> adapters;

    public DiscoveryStage(List<DiscoveryAdapter> adapters) {
        this.adapters = adapters;
    }

    /**
     * Calls all adapters independently; one failing adapter never blocks the rest.
     */
    public List<RawCandidate> discover() {
        List<RawCandidate> allCandidates = new ArrayList<>();

        for (DiscoveryAdapter adapter : adapters) {
            try {
                List<RawCandidate> fetched = adapter.fetchCandidates();
                log.info("DiscoveryStage: {} returned {} candidates", adapter.sourceName(), fetched.size());
                allCandidates.addAll(fetched);
            } catch (Exception e) {
                // Fault-isolated: one broken adapter must not prevent discovery from other sources
                log.warn("DiscoveryStage: adapter {} threw an exception (skipping): {}", adapter.sourceName(), e.getMessage());
            }
        }

        log.info("DiscoveryStage: total {} candidates discovered across {} adapters", allCandidates.size(), adapters.size());
        return allCandidates;
    }
}
