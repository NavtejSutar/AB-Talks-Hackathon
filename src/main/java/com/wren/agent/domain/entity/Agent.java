package com.wren.agent.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "persona_name", nullable = false)
    private String personaName;

    @Column(name = "persona_domain", nullable = false)
    private String personaDomain;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "post_sequence", nullable = false)
    private int postSequence = 0;

    @Column(name = "initialized_at", nullable = false, updatable = false)
    private Instant initializedAt = Instant.now();

    @Column(name = "last_tick_at")
    private Instant lastTickAt;

    @Column(name = "next_tick_at")
    private Instant nextTickAt;

    public Agent() {}

    public Agent(String personaName, String personaDomain) {
        this.personaName = personaName;
        this.personaDomain = personaDomain;
        this.status = "ACTIVE";
        this.postSequence = 0;
        this.initializedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getPersonaName() { return personaName; }
    public void setPersonaName(String personaName) { this.personaName = personaName; }

    public String getPersonaDomain() { return personaDomain; }
    public void setPersonaDomain(String personaDomain) { this.personaDomain = personaDomain; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPostSequence() { return postSequence; }
    public void setPostSequence(int postSequence) { this.postSequence = postSequence; }

    public Instant getInitializedAt() { return initializedAt; }
    public void setInitializedAt(Instant initializedAt) { this.initializedAt = initializedAt; }

    public Instant getLastTickAt() { return lastTickAt; }
    public void setLastTickAt(Instant lastTickAt) { this.lastTickAt = lastTickAt; }

    public Instant getNextTickAt() { return nextTickAt; }
    public void setNextTickAt(Instant nextTickAt) { this.nextTickAt = nextTickAt; }
}
