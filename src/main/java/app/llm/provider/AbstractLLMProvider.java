package app.llm.provider;

import app.llm.LLMConfig;
import app.llm.LLMService;

import java.util.Objects;

public abstract class AbstractLLMProvider implements LLMService {
    private final LLMConfig config;

    protected AbstractLLMProvider(LLMConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public LLMConfig config() {
        return config;
    }
}
