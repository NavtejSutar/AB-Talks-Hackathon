package com.wren.agent.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InitRequest {

    @NotNull(message = "persona is required")
    @Valid
    private PersonaDto persona;

    public InitRequest() {}

    public InitRequest(PersonaDto persona) {
        this.persona = persona;
    }

    public PersonaDto getPersona() { return persona; }
    public void setPersona(PersonaDto persona) { this.persona = persona; }

    public static class PersonaDto {
        @NotBlank(message = "persona name is required")
        private String name;

        @NotBlank(message = "persona domain is required")
        private String domain;

        public PersonaDto() {}

        public PersonaDto(String name, String domain) {
            this.name = name;
            this.domain = domain;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
    }
}
