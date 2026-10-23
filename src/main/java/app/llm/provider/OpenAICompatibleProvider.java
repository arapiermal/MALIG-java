package app.llm.provider;

import app.llm.*;
import app.core.capability.ToolCall;
import com.google.gson.*;
import java.net.http.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Non-streaming Chat Completions. The adapter proposes calls; only HMAS executes them. */
public final class OpenAICompatibleProvider extends AbstractLLMProvider {
    private static final Gson JSON = new GsonBuilder().registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
        @Override public void write(com.google.gson.stream.JsonWriter out, Instant value) throws IOException {
            if (value == null) out.nullValue(); else out.value(value.toString());
        }
        @Override public Instant read(com.google.gson.stream.JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) { in.nextNull(); return null; }
            return Instant.parse(in.nextString());
        }
    }).setStrictness(Strictness.STRICT).setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static JsonElement parse(String json) { return JSON.fromJson(json, JsonElement.class); }
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @FunctionalInterface interface Transport { Reply send(HttpRequest request) throws IOException, InterruptedException; }
    record Reply(int status, String body) { }
    private final String key;
    private final Transport transport;
    public OpenAICompatibleProvider(LLMConfig config, String key) {
        this(config, key, request -> {
            var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        });
    }
    OpenAICompatibleProvider(LLMConfig config, String key, Transport transport) {
        super(config); this.key = Objects.requireNonNull(key); this.transport = transport; CompatibleProfile.endpoint(config);
    }
    public static JsonObject requestBody(LLMConfig config, LLMInput input) {
        var profile = CompatibleProfile.find(config.provider()).orElseThrow();
        JsonObject body = new JsonObject(); body.addProperty("model", config.model());
        String tokens = config.options().getOrDefault("tokenParameter", profile.tokenParameter());
        if (!Set.of("max_tokens", "max_completion_tokens").contains(tokens)) throw LLMFailure.configuration("tokenParameter must be max_tokens or max_completion_tokens");
        body.addProperty(tokens, config.maxTokens());
        if (!config.providerTemperature()) body.addProperty("temperature", config.temperature());
        String toolMode = config.options().getOrDefault("toolMode", "enabled");
        if (!Set.of("enabled", "disabled").contains(toolMode)) throw LLMFailure.configuration("toolMode must be enabled or disabled");
        boolean tools = !"disabled".equals(toolMode);
        if (!tools && (!input.tools().isEmpty() || !input.turns().isEmpty())) throw LLMFailure.configuration("Function calling disabled for this model; use text-only work or a tool-capable model");
        String role = config.options().getOrDefault("instructionRole", "system");
        if (!Set.of("system", "developer").contains(role)) throw LLMFailure.configuration("instructionRole must be system or developer");
        JsonArray messages = new JsonArray();
        if (!input.systemPrompt().isBlank()) messages.add(JSON.toJsonTree(Map.of("role", role, "content", input.systemPrompt())));
        String context = String.join("\n", input.conversation().stream().filter(s -> !s.startsWith("Tool result for ")).toList());
        messages.add(JSON.toJsonTree(Map.of("role", "user", "content", "Retrieved context:\n" + context + "\n\nTask:\n" + input.userPrompt())));
        for (LLMTurn turn : input.turns()) {
            String original = turn.proposal().metadata().get("compatibleMessage");
            if (original == null) throw LLMFailure.response("Missing original compatible tool-call message");
            JsonObject assistant = parse(original).getAsJsonObject();
            JsonArray calls = assistant.getAsJsonArray("tool_calls");
            if (calls.size() != turn.results().size()) throw LLMFailure.response("Mismatched function results");
            messages.add(assistant);
            for (int i = 0; i < calls.size(); i++) {
                var result = turn.results().get(i);
                messages.add(JSON.toJsonTree(Map.of("role", "tool", "tool_call_id", calls.get(i).getAsJsonObject().get("id").getAsString(),
                        "content", JSON.toJson(Map.of("success", result.success(), "message", result.message(), "outputs", result.outputs())))));
            }
        }
        body.add("messages", messages);
        if (!input.tools().isEmpty()) {
            JsonArray definitions = new JsonArray();
            input.tools().forEach(t -> definitions.add(JSON.toJsonTree(Map.of("type", "function", "function",
                    Map.of("name", t.name(), "description", t.description(), "parameters", t.parametersSchema())))));
            body.add("tools", definitions); body.addProperty("tool_choice", "auto");
        }
        return body;
    }
    public static LLMOutput decode(String response, LLMConfig config) {
        try {
            JsonObject choice = parse(response).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
            String finish = choice.get("finish_reason").getAsString();
            if (!Set.of("stop", "tool_calls").contains(finish)) throw LLMFailure.response("Chat completion did not finish normally (" + (Set.of("length", "content_filter", "function_call").contains(finish) ? finish : "unsupported finish reason") + "); check output budget, content policy or model protocol");
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has("refusal") && !message.get("refusal").isJsonNull()) throw LLMFailure.response("Model refused this request");
            String text = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
            List<ToolCall> calls = new ArrayList<>(); Set<String> ids = new HashSet<>();
            if (message.has("tool_calls")) for (var item : message.getAsJsonArray("tool_calls")) {
                var call = item.getAsJsonObject(); String id = call.get("id").getAsString();
                if (id.isBlank() || !ids.add(id) || !"function".equals(call.get("type").getAsString())) throw LLMFailure.response("Invalid/duplicate tool-call ID or unsupported call type");
                var function = call.getAsJsonObject("function");
                var args = parse(function.get("arguments").getAsString());
                if (!args.isJsonObject()) throw LLMFailure.response("Function arguments must be a JSON object");
                Map<String, Object> arguments = JSON.fromJson(args, new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType());
                calls.add(new ToolCall(function.get("name").getAsString(), arguments));
            }
            if (calls.isEmpty() && (text.isBlank() || finish.equals("tool_calls"))) throw LLMFailure.response("Empty/inconsistent completion");
            // Keep only protocol fields; opaque provider reasoning extensions are not guessed or replayed.
            JsonObject replay = new JsonObject(); replay.addProperty("role", "assistant"); replay.add("content", message.get("content"));
            if (!calls.isEmpty()) replay.add("tool_calls", message.get("tool_calls"));
            return new LLMOutput(text, calls, Map.of("provider", config.provider(), "model", config.model(), "mode", "ONLINE",
                    "compatibleMessage", replay.toString(), "credentialSource", config.options().getOrDefault("credentialSource", "explicit adapter credential")));
        } catch (LLMFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw LLMFailure.response("Invalid Chat Completions response or malformed function arguments; no functions executed"); }
    }
    @Override public LLMOutput generate(LLMInput input) {
        var body = requestBody(config(), input);
        var request = HttpRequest.newBuilder(CompatibleProfile.endpoint(config())).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        Reply response;
        try { response = transport.send(request); }
        catch (HttpTimeoutException ex) { throw new LLMFailure(LLMFailure.Kind.TIMEOUT, null, "Provider request timed out; no automatic retry"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new LLMFailure(LLMFailure.Kind.CANCELLED, null, "Provider request interrupted"); }
        catch (IOException ex) { throw new LLMFailure(LLMFailure.Kind.TRANSPORT, null, "Provider connection failed; no automatic retry"); }
        if (response.status() < 200 || response.status() >= 300) throw httpFailure(response.status());
        LLMOutput output = decode(response.body(), config());
        Set<String> priorIds = new HashSet<>();
        for (var turn : input.turns()) {
            var message = parse(turn.proposal().metadata().get("compatibleMessage")).getAsJsonObject();
            message.getAsJsonArray("tool_calls").forEach(call -> priorIds.add(call.getAsJsonObject().get("id").getAsString()));
        }
        var message = parse(output.metadata().get("compatibleMessage")).getAsJsonObject();
        if (message.has("tool_calls")) for (var call : message.getAsJsonArray("tool_calls"))
            if (priorIds.contains(call.getAsJsonObject().get("id").getAsString())) throw LLMFailure.response("Provider reused a tool-call ID; duplicate proposal rejected before execution");
        return output;
    }
    static LLMFailure httpFailure(int status) {
        var kind = status == 429 ? LLMFailure.Kind.RATE_LIMIT : status == 408 || status == 504 ? LLMFailure.Kind.TIMEOUT
                : status >= 500 ? LLMFailure.Kind.UNAVAILABLE : LLMFailure.Kind.CONFIGURATION;
        return new LLMFailure(kind, status, "Chat Completions request failed. Check credentials, endpoint, model access and supported parameters/tools. Settings unchanged; no retry or fallback. Provider error body omitted to protect secrets.");
    }
}
