package app.scenario.synthetic;

import app.core.agent.*;
import app.core.environment.*;
import app.core.planning.*;
import java.util.*;

/** Algorithmic, scenario-specific planning strategy; core contains no resource policy. */
public final class ResourcePlanner implements Planner {
    private final int target;
    public ResourcePlanner() { this(80); }
    public ResourcePlanner(int target) {
        if (target < 0 || target > 100) throw new IllegalArgumentException("Target must be 0..100");
        this.target = target;
    }
    @Override public boolean supports(AbstractAgent agent, List<Goal> goals, AgentContext context) {
        return !goals.isEmpty() && goals.stream().allMatch(g -> g.description().toLowerCase(Locale.ROOT).contains("resource"))
                && List.of("inspect_environment", "evaluate_resources", "adjust_resources").stream()
                    .allMatch(name -> agent.capabilities().find(name).isPresent());
    }
    @Override public Plan createPlan(AbstractAgent agent, List<Goal> goals, AgentContext context) {
        int current = ((Number) context.environment().snapshot().facts().get("resourceLevel")).intValue();
        return new Plan("restore-resource-target", List.of(
                new PlanStep("observe", "Observe current resources", new AgentAction(agent.id(), "inspect_environment", Map.of())),
                new PlanStep("evaluate", "Evaluate shortage against target " + target, new AgentAction(agent.id(), "evaluate_resources", Map.of("target", target))),
                new PlanStep("allocate", "Restore resource target to " + target, new AgentAction(agent.id(), "adjust_resources", Map.of("amount", ResourceScenario.shortage(current, target))))));
    }
}
