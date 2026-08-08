package com.wren.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wren.agent.llm.json.StructuredJsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class StructuredJsonParserTest {

    private final StructuredJsonParser parser = new StructuredJsonParser(new ObjectMapper());

    public static class TestDto {
        private String status;
        private int count;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }

    @Test
    public void testParseCleanJson() throws Exception {
        String json = "{\"status\": \"OK\", \"count\": 42}";
        TestDto dto = parser.parse(json, TestDto.class);
        assertThat(dto.getStatus()).isEqualTo("OK");
        assertThat(dto.getCount()).isEqualTo(42);
    }

    @Test
    public void testParseJsonWithMarkdownFences() throws Exception {
        String raw = "```json\n{\"status\": \"OK\", \"count\": 42}\n```";
        TestDto dto = parser.parse(raw, TestDto.class);
        assertThat(dto.getStatus()).isEqualTo("OK");
        assertThat(dto.getCount()).isEqualTo(42);
    }

    @Test
    public void testParseMalformedJsonThrowsException() {
        String malformed = "This is not json at all";
        assertThatThrownBy(() -> parser.parse(malformed, TestDto.class))
                .isInstanceOf(StructuredJsonParser.StructuredJsonParseException.class);
    }
}
