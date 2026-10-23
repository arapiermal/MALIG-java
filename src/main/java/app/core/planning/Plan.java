package app.core.planning;

import java.util.List;

public record Plan(String name, List<PlanStep> steps, Decision decision) {
    public Plan(String name, List<PlanStep> steps) { this(name, steps, null); }
    public Plan {
        name = name == null ? "plan" : name;
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (decision != null && (decision.kind() == Decision.Kind.REJECT || decision.kind() == Decision.Kind.NO_ACTION) && !steps.isEmpty())
            throw new IllegalArgumentException("Reject/no-action decisions cannot propose effects");
    }

    public static Plan empty() {
        return new Plan("empty", List.of());
    }
}
