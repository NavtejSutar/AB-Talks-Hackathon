package com.wren.agent.discovery;

import com.wren.agent.pipeline.model.RawCandidate;

import java.util.List;

public interface DiscoveryAdapter {

    String sourceName();

    List<RawCandidate> fetchCandidates();
}
