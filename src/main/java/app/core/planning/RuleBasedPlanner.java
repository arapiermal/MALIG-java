package app.core.planning;

import app.core.agent.*;
import app.core.environment.*;
import java.util.*;

public class RuleBasedPlanner implements Planner {
    public boolean supports(List<Goal> goals) {
        return goals != null && !goals.isEmpty() && goals.stream().allMatch(g -> actionName(g) != null);
    }
    @Override public boolean supports(AbstractAgent agent, List<Goal> goals, AgentContext context) {
        return supports(goals) && goals.stream().allMatch(g -> agent.capabilities().find(actionName(g)).isPresent());
    }
    @Override public Plan createPlan(AbstractAgent agent, List<Goal> goals, AgentContext context) {
        if (!supports(agent, goals, context)) throw new IllegalArgumentException("Unsupported rule task or missing capability");
        return new Plan("rule-based-plan", goals.stream().map(goal -> {
            String name = actionName(goal);
            Map<String, Object> args = name.equals("remember")
                    ? Map.of("content", goal.description().trim().substring(goal.description().trim().indexOf(' ') + 1))
                    : Map.of();
            return new PlanStep(goal.id().toString(), goal.description(), new AgentAction(agent.id(), name, args));
        }).toList());
    }
    private String actionName(Goal goal) {
        String text = goal.description().strip().toLowerCase(Locale.ROOT);
        if (text.startsWith("remember ") || text.startsWith("note ")) return "remember";
        if (text.startsWith("inspect") || text.startsWith("observe")) return "inspect_environment";
        return null;
    }
}
