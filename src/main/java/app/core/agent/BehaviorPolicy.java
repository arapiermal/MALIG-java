package app.core.agent;

import app.core.capability.ToolCall;
import app.core.environment.*;
import app.core.message.*;
import app.core.planning.*;
import app.core.runtime.*;
import java.util.*;
import java.util.function.*;

/** Small typed composition of reactions. Produces existing Plans; owns no scheduler, executor or mailbox. */
public final class BehaviorPolicy implements AgentPolicy {
    public enum Source { OBSERVATION, STATE }
    public enum Comparison { LT, LE, EQ, GE, GT }
    public record Input(AbstractAgent agent, Goal goal, AgentMessage message, WorldStateSnapshot observation, Map<String, Object> state) {
        public <T> T value(Source source, String key, Class<T> type) {
            Object value = (source == Source.OBSERVATION ? observation.facts() : state).get(key);
            if (!type.isInstance(value)) throw new TaskFailure("Missing/wrongly typed " + source + " value: " + key + "; expected " + type.getSimpleName());
            return type.cast(value);
        }
        public AgentRequest response(String peer) {
            if (message == null || message.type() != MessageType.RESPONSE) throw new TaskFailure("A correlated peer response is required");
            UUID id;
            try { id = UUID.fromString(message.metadata().get("requestId")); }
            catch (RuntimeException ex) { throw new TaskFailure("Invalid response correlation"); }
            AgentRequest request = agent.context().requests().stream().filter(r -> r.id().equals(id) && r.requesterId().equals(agent.id())
                    && r.recipientId().equals(message.senderId()) && r.onResult().equals(goal.description()) && r.status() != AgentRequest.Status.PENDING)
                    .findFirst().orElseThrow(() -> new TaskFailure("Response does not match this peer/continuation"));
            // A runtime-issued cancellation may arrive after the named recipient was removed.
            // It can only defer work; it must not depend on resolving that absent agent again.
            if (request.status() == AgentRequest.Status.CANCELLED) return request;
            UUID recipient;
            try { recipient = agent.context().resolvePeer(peer); }
            catch (IllegalArgumentException ex) { throw new TaskFailure("Response peer is no longer available: " + peer); }
            if (!request.recipientId().equals(recipient)) throw new TaskFailure("Response does not match this peer/continuation");
            return request;
        }
        public Plan decide(Decision.Kind kind, String reason, ToolCall... calls) {
            List<PlanStep> steps = new ArrayList<>();
            for (ToolCall call : calls) steps.add(new PlanStep("behavior-" + steps.size(), call.name(), new AgentAction(agent.id(), call.name(), call.arguments())));
            return new Plan("behavior", steps, new Decision(kind, reason));
        }
    }
    public record Rule(Predicate<Input> trigger, Function<Input, Plan> response) {
        public Rule { Objects.requireNonNull(trigger); Objects.requireNonNull(response); }
    }
    private final List<Rule> rules;
    public BehaviorPolicy(Rule... rules) { this.rules = List.of(rules); if (this.rules.isEmpty()) throw new IllegalArgumentException("At least one reaction is required"); }
    public static Predicate<Input> goal(String task) { return i -> i.goal().description().equals(task); }
    public static Predicate<Input> message(MessageType type) { return i -> i.message() != null && i.message().type() == type; }
    public static Predicate<Input> number(Source source, String key, Comparison comparison, double threshold) {
        if (!Double.isFinite(threshold)) throw new IllegalArgumentException("Finite threshold required");
        return input -> {
            double actual = input.value(source, key, Number.class).doubleValue();
            if (!Double.isFinite(actual)) throw new TaskFailure("Non-finite condition value: " + key);
            return switch (comparison) { case LT -> actual < threshold; case LE -> actual <= threshold; case EQ -> actual == threshold; case GE -> actual >= threshold; case GT -> actual > threshold; };
        };
    }
    public static <T> Predicate<Input> equal(Source source, String key, Class<T> type, T expected) { return i -> Objects.equals(i.value(source, key, type), expected); }
    public static Function<Input, Plan> branch(Predicate<Input> condition, Function<Input, Plan> yes, Function<Input, Plan> no) {
        return input -> (condition.test(input) ? yes : no).apply(input);
    }
    private Input input(AbstractAgent agent, List<Goal> goals) {
        if (goals.size() != 1) throw new TaskFailure("Behavior requires one attributable input");
        return new Input(agent, goals.getFirst(), agent.activeMessage().orElse(null), agent.context().environment().snapshot(), agent.memory().state());
    }
    @Override public boolean supports(AbstractAgent agent, List<Goal> goals) { return goals.size() == 1 && rules.stream().anyMatch(r -> r.trigger().test(input(agent, goals))); }
    @Override public Plan createPlan(AbstractAgent agent, List<Goal> goals) {
        Input input = input(agent, goals);
        return rules.stream().filter(r -> r.trigger().test(input)).findFirst().orElseThrow(() -> new TaskFailure("Unsupported behavior input"))
                .response().apply(input);
    }
}
