package com.wren.agent.service;

import com.wren.agent.api.dto.InitRequest;
import com.wren.agent.api.dto.InitResponse;
import com.wren.agent.domain.entity.Agent;
import com.wren.agent.domain.repository.AgentRepository;
import com.wren.agent.persona.PersonaProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository agentRepository;

    public AgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Transactional
    public InitResponse initializeAgent(InitRequest request) {
        String personaName = request.getPersona().getName();
        String personaDomain = request.getPersona().getDomain();

        Agent agent = new Agent(personaName, personaDomain);
        agent.setSystemPrompt(PersonaProfile.buildSystemPrompt(PersonaProfile.getDisplayName(personaName)));
        Agent savedAgent = agentRepository.save(agent);

        return new InitResponse(savedAgent.getId().toString());
    }

    public Optional<Agent> findById(UUID agentId) {
        return agentRepository.findById(agentId);
    }
}
