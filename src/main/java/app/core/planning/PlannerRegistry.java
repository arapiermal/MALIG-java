package app.core.planning;

import app.core.agent.*;
import app.core.environment.Goal;
import app.core.runtime.ExecutionEventType;
import java.util.*;

/** Ordered, extensible selection by configured strategy, goal support and granted capabilities. */
public final class PlannerRegistry implements Planner {
    private final Map<String, Planner> strategies = new LinkedHashMap<>();
    private final String selection;
    private volatile String lastSelected = "";
    public PlannerRegistry(String selection) { this.selection = selection == null ? "auto" : selection; }
    public PlannerRegistry register(String name, Planner planner) {
        if (strategies.putIfAbsent(name, planner) != null) throw new IllegalArgumentException("Duplicate planner " + name);
        return this;
    }
    public Set<String> names() { return Set.copyOf(strategies.keySet()); }
    public String lastSelected() { return lastSelected; }
    @Override public Plan createPlan(AbstractAgent agent, List<Goal> goals, AgentContext context) {
        var selected = strategies.entrySet().stream().filter(e -> selection.equals("auto") || selection.equals(e.getKey()))
                .filter(e -> e.getValue().supports(agent, goals, context)).findFirst()
                .orElseThrow(() -> new app.core.runtime.TaskFailure("No suitable planner for selection: " + selection));
        lastSelected = selected.getKey();
        context.trace().publish(app.core.runtime.ExecutionEvent.of(ExecutionEventType.PLANNER_SELECTED,
                agent.id(), "planner", lastSelected));
        return selected.getValue().createPlan(agent, goals, context);
    }
}
