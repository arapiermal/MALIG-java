package app.llm.provider;

import app.llm.*;
import java.net.URI;
import java.util.*;

/** Protocol defaults only, never a model whitelist or credential fallback. */
public record CompatibleProfile(String id, String baseUrl, String model, String tokenParameter) {
    public static final List<CompatibleProfile> ALL = List.of(
            new CompatibleProfile("openai", "https://api.openai.com/v1", "gpt-4.1-mini", "max_completion_tokens"),
            new CompatibleProfile("groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "max_tokens"),
            new CompatibleProfile("venice", "https://api.venice.ai/api/v1", "zai-org-glm-5", "max_tokens"),
            new CompatibleProfile("custom", "", "", "max_tokens"));
    public static Optional<CompatibleProfile> find(String id) { return ALL.stream().filter(p -> p.id.equalsIgnoreCase(id)).findFirst(); }
    public static URI endpoint(LLMConfig config) {
        try {
            var profile = find(config.provider()).orElseThrow();
            String base = config.options().getOrDefault("baseUrl", profile.baseUrl()).strip().replaceAll("/+$", "");
            URI uri = URI.create(base);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return URI.create(base + "/chat/completions");
        } catch (RuntimeException ex) { throw LLMFailure.configuration("Enter an HTTPS API base URL without credentials, query or fragment (ending before /chat/completions)"); }
    }
}
