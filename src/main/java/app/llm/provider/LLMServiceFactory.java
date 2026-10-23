package app.llm.provider;

import app.llm.*;
import java.util.*;

public final class LLMServiceFactory {
    public static final String PROVIDER_PROPERTY = "hmas.llm.provider";
    public static final String MODEL_PROPERTY = "hmas.llm.model";
    public static final String TEMPERATURE_MODE_PROPERTY = "hmas.llm.temperatureMode";
    private LLMServiceFactory() { }
    public static LLMConfig defaults() {
        String provider = setting(PROVIDER_PROPERTY, "HMAS_LLM_PROVIDER", "offline");
        return new LLMConfig(provider, defaultModel(provider), 0.2, 4096,
                defaultOptions(provider));
    }
    public static String defaultModel(String provider) {
        if (isOffline(provider)) return "offline-rules";
        String fallback = CompatibleProfile.find(provider).map(CompatibleProfile::model).orElse("gemini-2.5-flash");
        String selected = setting(PROVIDER_PROPERTY, "HMAS_LLM_PROVIDER", "offline");
        String model = selected.equalsIgnoreCase(provider) ? setting(MODEL_PROPERTY, "HMAS_LLM_MODEL", fallback) : fallback;
        return model.equals("offline-rules") ? fallback : model;
    }
    public static List<String> providers() { return List.of("offline", "google", "openai", "groq", "venice", "custom"); }
    public static Map<String, String> defaultOptions(String provider) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(LLMConfig.TEMPERATURE_MODE, setting(TEMPERATURE_MODE_PROPERTY, "HMAS_LLM_TEMPERATURE_MODE", "provider-default"));
        CompatibleProfile.find(provider).ifPresent(profile -> {
            for (String key : List.of("baseUrl", "toolMode", "tokenParameter", "instructionRole")) {
                String fallback = switch (key) { case "baseUrl" -> profile.baseUrl(); case "toolMode" -> "enabled"; case "tokenParameter" -> profile.tokenParameter(); default -> "system"; };
                options.put(key, System.getProperty("hmas.llm." + provider + "." + key, fallback));
            }
        });
        return Map.copyOf(options);
    }
    public static boolean isOffline(String provider) { return "offline".equalsIgnoreCase(provider) || "deterministic".equalsIgnoreCase(provider); }
    public static String label(String provider) {
        return isOffline(provider) ? "Offline deterministic test provider" : "google".equalsIgnoreCase(provider)
                ? "Google Gemini (online)" : CompatibleProfile.find(provider).map(p -> p.id() + " (online Chat Completions)").orElse("Unsupported provider: " + provider);
    }
    public static LLMService fromEnvironment() { return forConfig(defaults()); }
    public static LLMService forConfig(LLMConfig config) { return forConfig(config, CredentialStore.current()); }
    public static LLMService forSelection(String provider, String model, double temperature) {
        return forSelection(provider, model, temperature, CredentialStore.current());
    }
    public static LLMService forSelection(String provider, String model, double temperature, CredentialStore credentials) {
        return forConfig(new LLMConfig(provider, model, temperature, 1024, Map.of()), credentials);
    }
    public static LLMService forConfig(LLMConfig requested, CredentialStore credentials) {
        String provider = requested.provider(), model = requested.model();
        if (isOffline(provider)) return new DeterministicLLMService();
        if (!"google".equalsIgnoreCase(provider) && CompatibleProfile.find(provider).isEmpty()) throw LLMFailure.configuration("Unsupported provider selection");
        var credential = credentials.resolve(provider).orElseThrow(() -> LLMFailure.configuration(
                label(provider) + " selected but no credential is configured. Open APIs, or explicitly select the offline test provider."));
        if (model == null || model.isBlank() || model.equals("offline-rules")) throw LLMFailure.configuration("Enter a model ID; manual model IDs have no whitelist");
        Map<String, String> options = new LinkedHashMap<>(requested.options());
        options.put("credentialSource", credential.source());
        LLMConfig effective = new LLMConfig(provider.toLowerCase(Locale.ROOT), model, requested.temperature(), requested.maxTokens(), options);
        return "google".equalsIgnoreCase(provider) ? new GoogleProvider(effective, credential.key()) : new OpenAICompatibleProvider(effective, credential.key());
    }
    public static boolean googleKeyDetected() { return CredentialStore.current().resolve().isPresent(); }
    private static String setting(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(env);
        return value == null || value.isBlank() ? fallback : value;
    }
}
