package com.wren.agent.llm.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StructuredJsonParser {

    private final ObjectMapper objectMapper;

    public StructuredJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T parse(String rawText, Class<T> targetClass) throws StructuredJsonParseException {
        if (rawText == null || rawText.trim().isEmpty()) {
            throw new StructuredJsonParseException("Raw text is null or empty");
        }

        String cleanedJson = stripMarkdownCodeFences(rawText);

        try {
            return objectMapper.readValue(cleanedJson, targetClass);
        } catch (JsonProcessingException e) {
            throw new StructuredJsonParseException("Failed to parse JSON into " + targetClass.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public String stripMarkdownCodeFences(String input) {
        if (input == null) return "";
        String trimmed = input.trim();

        // Strip ```json ... ``` or ``` ... ```
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak != -1) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }

        return trimmed.trim();
    }

    public static class StructuredJsonParseException extends Exception {
        public StructuredJsonParseException(String message) {
            super(message);
        }

        public StructuredJsonParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
