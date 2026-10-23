package app.llm;

import java.util.Map;

public record LLMConfig(String provider, String model, double temperature, int maxTokens, Map<String, String> options) {
    public static final String TEMPERATURE_MODE = "temperatureMode";
    public boolean providerTemperature() { return "provider-default".equals(options.get(TEMPERATURE_MODE)); }
    public LLMConfig {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2 || maxTokens < 1)
            throw new IllegalArgumentException("Invalid LLM sampling configuration");
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static LLMConfig local(String model) {
        return new LLMConfig("local", model, 0.0, 1024, Map.of());
    }
}
