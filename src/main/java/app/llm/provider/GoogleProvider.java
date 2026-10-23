package app.llm.provider;

import app.llm.*;
import app.core.capability.ToolCall;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.google.genai.errors.ApiException;
import java.util.*;

/** The only SDK boundary. Native function responses replay original model content/signatures. */
public final class GoogleProvider extends AbstractLLMProvider {
    private final String apiKey;
    public GoogleProvider(LLMConfig config, String apiKey) { super(config); this.apiKey = Objects.requireNonNull(apiKey); }
    public static List<Content> contents(LLMInput input) {
        List<Content> contents = new ArrayList<>();
        String context = String.join("\n", input.conversation().stream().filter(s -> !s.startsWith("Tool result for ")).toList());
        contents.add(Content.builder().role("user").parts(Part.builder()
                .text("Retrieved context:\n" + context + "\n\nTask:\n" + input.userPrompt()).build()).build());
        for (LLMTurn turn : input.turns()) {
            String opaque = turn.proposal().metadata().get("googleContent");
            if (opaque == null) throw new IllegalArgumentException("Missing original Gemini continuation content");
            Content modelContent = Content.fromJson(opaque);
            contents.add(modelContent);
            List<FunctionCall> calls = modelContent.parts().orElse(List.of()).stream()
                    .flatMap(p -> p.functionCall().stream()).toList();
            if (calls.size() != turn.results().size()) throw new IllegalArgumentException("Mismatched Gemini function results");
            List<Part> responses = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                var result = turn.results().get(i);
                FunctionResponse.Builder response = FunctionResponse.builder().name(calls.get(i).name().orElseThrow())
                        .response(Map.of("success", result.success(), "message", result.message(), "outputs", jsonValue(result.outputs())));
                calls.get(i).id().ifPresent(response::id);
                responses.add(Part.builder().functionResponse(response.build()).build());
            }
            contents.add(Content.builder().role("user").parts(responses).build());
        }
        return List.copyOf(contents);
    }
    private static Object jsonValue(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), jsonValue(v))); return normalized;
        }
        if (value instanceof Collection<?> items) return items.stream().map(GoogleProvider::jsonValue).toList();
        return String.valueOf(value);
    }
    public static LLMOutput decode(GenerateContentResponse response, LLMConfig config) {
        String finish = response.candidates().filter(c -> !c.isEmpty()).flatMap(c -> c.getFirst().finishReason())
                .map(Object::toString).orElse("");
        if (finish.equals("MAX_TOKENS")) throw LLMFailure.response("Gemini reached the output-token limit; increase maximum output tokens or shorten the task");
        if (finish.equals("UNEXPECTED_TOOL_CALL")) throw LLMFailure.response("Gemini did not complete normally (finish reason: UNEXPECTED_TOOL_CALL): the model attempted a function call incompatible with this request. Ordinary conversations and Provider Prompt are text-only; use a tool-enabled task for granted functions. No unexpected function was executed.");
        if (!finish.isEmpty() && !finish.equals("STOP") && !finish.equals("FINISH_REASON_UNSPECIFIED"))
            throw LLMFailure.response("Gemini did not complete normally (finish reason: " + finish + ")");
        Content content = response.candidates().filter(c -> !c.isEmpty()).flatMap(c -> c.getFirst().content())
                .orElseThrow(() -> LLMFailure.response("Gemini returned no candidate content; check safety/response limits"));
        List<ToolCall> calls = new ArrayList<>(); StringBuilder text = new StringBuilder();
        content.parts().orElse(List.of()).forEach(part -> {
            if (!part.thought().orElse(false)) part.text().ifPresent(text::append);
            part.functionCall().ifPresent(call -> calls.add(new ToolCall(call.name().orElse(""), call.args().orElse(Map.of()))));
        });
        if (text.isEmpty() && calls.isEmpty()) throw LLMFailure.response("Gemini returned no usable text or calls");
        return new LLMOutput(text.toString(), calls, Map.of("provider", "google", "model", config.model(),
                "mode", "ONLINE", "googleContent", content.toJson(),
                "credentialSource", config.options().getOrDefault("credentialSource", "explicit adapter credential")));
    }
    /** No model-name branches. Explicit sampling overrides are sent unchanged; defaults are omitted. */
    public static GenerateContentConfig requestOptions(LLMConfig config, LLMInput input) {
        GenerateContentConfig.Builder options = GenerateContentConfig.builder()
                .maxOutputTokens(config.maxTokens())
                .httpOptions(HttpOptions.builder().timeout(30000).retryOptions(HttpRetryOptions.builder().attempts(1).build()).build())
                .automaticFunctionCalling(AutomaticFunctionCallingConfig.builder().disable(true).build());
        if (!config.providerTemperature()) options.temperature((float) config.temperature());
        if (!input.systemPrompt().isBlank()) options.systemInstruction(Content.builder()
                .parts(Part.builder().text(input.systemPrompt()).build()).build());
        if (!input.tools().isEmpty()) options.tools(Tool.builder().functionDeclarations(input.tools().stream()
                .map(t -> FunctionDeclaration.builder().name(t.name()).description(t.description())
                        .parametersJsonSchema(t.parametersSchema()).build()).toList()).build());
        return options.build();
    }
    /** Deliberately one HTTP attempt: no automatic replay of a tool loop or quota-consuming work. */
    @Override public LLMOutput generate(LLMInput input) {
        GenerateContentResponse response;
        try (Client client = Client.builder().apiKey(apiKey).build()) {
            response = client.models.generateContent(config().model(), contents(input), requestOptions(config(), input));
        } catch (ApiException ex) {
            throw httpFailure(ex.code());
        } catch (RuntimeException ex) {
            throw transportFailure(ex);
        }
        return decode(response, config());
    }
    public static LLMFailure httpFailure(int code) {
        return switch (code) {
            case 400, 422 -> new LLMFailure(LLMFailure.Kind.CONFIGURATION, code, "Gemini rejected the request. Check model ID, supported parameters and tool schema; settings were not changed");
            case 401, 403 -> new LLMFailure(LLMFailure.Kind.CONFIGURATION, code, "Gemini credential rejected or model access denied. Correct credentials/access in APIs before starting new work");
            case 404 -> new LLMFailure(LLMFailure.Kind.CONFIGURATION, code, "Gemini model/endpoint not found. Check the exact model ID and account access");
            case 429 -> new LLMFailure(LLMFailure.Kind.RATE_LIMIT, code, "Gemini quota/rate limit exceeded. Wait for quota recovery or check billing/limits before starting a new attempt; no automatic retry");
            case 408, 504 -> new LLMFailure(LLMFailure.Kind.TIMEOUT, code, "Gemini request timed out. Completion is uncertain; inspect prior effects before new work");
            default -> new LLMFailure(code >= 500 ? LLMFailure.Kind.UNAVAILABLE : LLMFailure.Kind.CONFIGURATION, code,
                    code >= 500 ? "Gemini service unavailable. Try new work later; this response does not establish model/parameter incompatibility"
                            : "Gemini rejected the request. Check provider configuration; no automatic retry");
        };
    }
    public static LLMFailure transportFailure(Throwable error) {
        boolean timeout = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException || cause.getClass().getName().equals("java.net.http.HttpTimeoutException")
                    || cause instanceof java.util.concurrent.TimeoutException) { timeout = true; break; }
        }
        // Do not retain an SDK cause: messages/URLs may contain credentials or request bodies.
        return new LLMFailure(timeout ? LLMFailure.Kind.TIMEOUT : LLMFailure.Kind.TRANSPORT, null,
                timeout ? "Gemini timed out after the bounded request wait. No automatic retry; inspect prior tool effects before new work"
                        : "Gemini transport failed. Check connectivity and try new work later; no automatic retry");
    }
}
