package app.llm;

import java.time.Instant;

/** Observable reasoning status; ONLINE describes the provider, not a connectivity guarantee. */
public record LLMStatus(String requestedProvider, String effectiveProvider, String model,
                        String mode, String outcome, String detail, Instant attemptedAt) {
    public static LLMStatus configured(LLMConfig config) {
        if (config == null) return new LLMStatus("none", "none", "", "NOT_USED", "NOT_USED", "", null);
        return new LLMStatus(config.provider(), "not invoked", config.model(), mode(config.provider()),
                "NOT_TESTED", "Configuration does not verify credentials or connectivity", null);
    }
    public static String mode(String provider) {
        return switch (provider.toLowerCase(java.util.Locale.ROOT)) {
            case "google", "openai", "groq", "venice", "custom" -> "ONLINE";
            case "offline", "deterministic" -> "OFFLINE_TEST";
            default -> "CUSTOM";
        };
    }
}
