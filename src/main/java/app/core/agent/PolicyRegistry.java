package app.core.agent;

import app.core.environment.Goal;
import app.core.planning.*;
import java.util.*;
import java.util.function.Function;

/** Named deterministic policies; options are validated when the policy is constructed. */
public final class PolicyRegistry {
    public record Definition(String id, String description, Map<String, String> defaults,
                             Function<Map<String, String>, AgentPolicy> factory) {
        public Definition { defaults = Map.copyOf(defaults); }
    }
    private final Map<String, Definition> policies = new LinkedHashMap<>();
    public PolicyRegistry() {
        BehaviorTemplates.register(this);
        register("conditional-peer", "On the exact trigger task, optionally require fact < below, request a named peer, then record accepted/rejected actual result content. Blank fact means unconditional; blank acceptContains accepts any successful content. Requires request_agent and remember.",
                Map.of("trigger", "Review condition", "peer", "", "task", "Inspect environment", "fact", "", "below", "80", "acceptContains", ""), PeerWorkPolicy::new);
        register("rules", "Inspect/observe the environment or remember/note text.", Map.of(), options -> new AgentPolicy() {
            private final RuleBasedPlanner rules = new RuleBasedPlanner();
            public boolean supports(AbstractAgent agent, List<Goal> goals) { return rules.supports(agent, goals, agent.context()); }
            public Plan createPlan(AbstractAgent agent, List<Goal> goals) { return rules.createPlan(agent, goals, agent.context()); }
        });
    }
    public void register(String id, String description, Map<String, String> defaults,
                         Function<Map<String, String>, AgentPolicy> factory) {
        if (policies.putIfAbsent(id, new Definition(id, description, defaults, factory)) != null)
            throw new IllegalArgumentException("Duplicate policy: " + id);
    }
    public List<Definition> definitions() { return List.copyOf(policies.values()); }
    public AgentPolicy resolve(String id, Map<String, String> options) {
        Definition definition = Optional.ofNullable(policies.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown deterministic policy: " + id));
        if (!definition.defaults().keySet().containsAll(options.keySet())) throw new IllegalArgumentException("Unknown options for policy: " + id);
        Map<String, String> merged = new LinkedHashMap<>(definition.defaults()); merged.putAll(options);
        return definition.factory().apply(Map.copyOf(merged));
    }
}
