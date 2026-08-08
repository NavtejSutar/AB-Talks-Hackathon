package com.wren.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LlmProviderRouterTest {

    static class MockProvider implements LlmProvider {
        private final String name;
        private final boolean available;
        private final boolean shouldFail;

        MockProvider(String name, boolean available, boolean shouldFail) {
            this.name = name;
            this.available = available;
            this.shouldFail = shouldFail;
        }

        @Override public String name() { return name; }
        @Override public boolean isAvailable() { return available; }

        @Override
        public LlmResponse complete(LlmRequest request) throws LlmProviderException {
            if (shouldFail) {
                throw new LlmProviderException(name, "Simulated failure");
            }
            return new LlmResponse("Success from " + name, name, 100);
        }
    }

    @Test
    public void testFailoverToSecondProvider() {
        MockProvider p1 = new MockProvider("gemini", true, true);
        MockProvider p2 = new MockProvider("groq", true, false);

        LlmProviderRouter router = new LlmProviderRouter(List.of(p1, p2));
        LlmProviderRouter.RouterResult result = router.complete(new LlmRequest("sys", "user"));

        assertThat(result.getResponse().getProviderName()).isEqualTo("groq");
        assertThat(result.getResponse().getContent()).isEqualTo("Success from groq");
        assertThat(result.getFailoverCount()).isEqualTo(1);
    }

    @Test
    public void testAllProvidersFailThrowsException() {
        MockProvider p1 = new MockProvider("gemini", true, true);
        MockProvider p2 = new MockProvider("groq", true, true);

        LlmProviderRouter router = new LlmProviderRouter(List.of(p1, p2));

        assertThatThrownBy(() -> router.complete(new LlmRequest("sys", "user")))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("All configured LLM providers failed");
    }

    @Test
    public void testSkipUnavailableProviders() {
        MockProvider p1 = new MockProvider("gemini", false, false);
        MockProvider p2 = new MockProvider("groq", true, false);

        LlmProviderRouter router = new LlmProviderRouter(List.of(p1, p2));
        LlmProviderRouter.RouterResult result = router.complete(new LlmRequest("sys", "user"));

        assertThat(result.getResponse().getProviderName()).isEqualTo("groq");
        assertThat(result.getFailoverCount()).isEqualTo(0);
    }
}
