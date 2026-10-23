package app.core.agent;

import app.core.capability.ToolCall;
import app.core.message.MessageType;
import app.core.planning.*;
import app.core.runtime.AgentRequest;
import java.util.*;
import java.util.function.Predicate;
import static app.core.agent.BehaviorPolicy.*;
import static app.core.planning.Decision.Kind.*;

/** Configurable templates over the typed Java API, not a text behavior language. */
final class BehaviorTemplates {
    private BehaviorTemplates() { }
    static void register(PolicyRegistry registry) {
        Map<String, String> defaults = Map.of("trigger", "Evaluate condition", "source", "OBSERVATION", "field", "resourceLevel",
                "comparison", "LT", "threshold", "70", "tool", "set_alert", "argument", "alert", "valueType", "BOOLEAN", "value", "true", "otherwise", "NO_ACTION");
        registry.register("threshold-actions", "Trigger: exact configured goal or COMMAND/QUERY/REQUEST text. Read a numeric observation/state field; compare with threshold. True: ACCEPT and call the configured tool. False: configured otherwise decision (NO_ACTION, REJECT or DEFER), no effects. Blank tool publishes a decision only. Tool arguments are typed; execution still requires a grant. Java definitions may compose multiple steps.",
                defaults, BehaviorTemplates::threshold);
        Map<String, String> peerDefaults = new LinkedHashMap<>(defaults);
        peerDefaults.putAll(Map.of("trigger", "Request decision", "peer", "", "task", "Evaluate condition", "freshnessFact", "resourceLevel", "resultKey", "decision"));
        registry.register("guarded-peer", "Trigger: exact task and numeric condition. True: DEFER and request the named peer; requires request_agent. On the correlated result: require success, unchanged freshnessFact and a structured {kind, reason} decision at resultKey. Only ACCEPT invokes the configured tool. Failed/stale work DEFERs; malformed data REJECTs; NO_ACTION/REJECT never execute effects. No automatic retry. Blank tool records ACCEPT without an effect.",
                peerDefaults, BehaviorTemplates::peer);
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Configure " + key); return value.strip();
    }
    private static Predicate<Input> condition(Map<String, String> options) {
        Source source = Source.valueOf(required(options, "source"));
        Comparison comparison = Comparison.valueOf(required(options, "comparison"));
        double threshold;
        try { threshold = Double.parseDouble(required(options, "threshold")); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("threshold must be numeric"); }
        return number(source, required(options, "field"), comparison, threshold);
    }
    private static ToolCall[] actions(Map<String, String> options) {
        String tool = options.get("tool").strip();
        if (tool.isEmpty()) return new ToolCall[0];
        String argument = options.get("argument").strip();
        if (argument.isEmpty()) return new ToolCall[]{new ToolCall(tool, Map.of())};
        String value = options.get("value"); Object typed;
        try {
            typed = switch (required(options, "valueType")) {
                case "STRING" -> value;
                case "BOOLEAN" -> { if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("BOOLEAN value must be true or false"); yield Boolean.valueOf(value); }
                case "INTEGER" -> Long.valueOf(value);
                case "NUMBER" -> { double number = Double.parseDouble(value); if (!Double.isFinite(number)) throw new IllegalArgumentException("Finite NUMBER required"); yield number; }
                default -> throw new IllegalArgumentException("valueType must be STRING, BOOLEAN, INTEGER or NUMBER");
            };
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("value does not match valueType"); }
        return new ToolCall[]{new ToolCall(tool, Map.of(argument, typed))};
    }
    private static String describe(Map<String, String> options) { return options.get("source") + "." + options.get("field") + " " + options.get("comparison") + " " + options.get("threshold"); }
    private static Decision.Kind otherwise(Map<String, String> options) {
        var kind = Decision.Kind.valueOf(required(options, "otherwise"));
        if (kind == ACCEPT) throw new IllegalArgumentException("otherwise must be NO_ACTION, REJECT or DEFER");
        return kind;
    }
    private static AgentPolicy threshold(Map<String, String> options) {
        var condition = condition(options); var calls = actions(options); var otherwise = otherwise(options); String trigger = required(options, "trigger"), description = describe(options);
        return new BehaviorPolicy(new Rule(goal(trigger), branch(condition,
                i -> i.decide(ACCEPT, "Condition matched: " + description, calls),
                i -> i.decide(otherwise, "Condition false: " + description))));
    }
    private static AgentPolicy peer(Map<String, String> options) {
        var condition = condition(options); var calls = actions(options); var otherwise = otherwise(options);
        String trigger = required(options, "trigger"), peer = required(options, "peer"), task = required(options, "task"),
                freshness = required(options, "freshnessFact"), resultKey = required(options, "resultKey"), continuation = "[guarded-peer] " + trigger;
        return new BehaviorPolicy(
                new Rule(goal(trigger).and(message(MessageType.RESPONSE).negate()), branch(condition,
                        i -> {
                            if (!i.observation().facts().containsKey(freshness)) return i.decide(REJECT, "Missing freshness observation: " + freshness);
                            return i.decide(DEFER, "Condition matched; waiting for a typed decision from " + peer,
                                    new ToolCall("request_agent", Map.of("recipientId", peer, "task", task, "onResult", continuation)));
                        }, i -> i.decide(otherwise, "Condition false: " + describe(options)))),
                new Rule(goal(continuation).and(message(MessageType.RESPONSE)), i -> {
                    var response = i.response(peer);
                    if (response.status() != AgentRequest.Status.SUCCEEDED) return i.decide(DEFER, "Peer request " + response.status() + ": " + response.result());
                    if (!response.observedFacts().containsKey(freshness) || !Objects.equals(response.observedFacts().get(freshness), i.observation().facts().get(freshness)))
                        return i.decide(DEFER, "Stale observation: " + freshness + "; submit fresh work to reconsider");
                    Optional<Decision> decision = Decision.from(response.outputs().get(resultKey));
                    if (decision.isEmpty()) return i.decide(REJECT, "Invalid/missing structured decision at " + resultKey + "; request success alone grants no action");
                    Decision advice = decision.orElseThrow();
                    if (advice.kind() != ACCEPT) return i.decide(advice.kind(), "Peer decision: " + advice.reason());
                    if (!condition.test(i)) return i.decide(NO_ACTION, "Condition no longer holds; peer acceptance does not override local conditions");
                    return i.decide(ACCEPT, "Correlated peer decision accepted: " + advice.reason(), calls);
                }));
    }
}
