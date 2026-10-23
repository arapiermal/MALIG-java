package app.core.planning;

import app.core.environment.AgentAction;

public record PlanStep(String id, String description, AgentAction action) {
    public PlanStep {
        id = id == null || id.isBlank() ? "step" : id;
        description = description == null ? "" : description;
    }
}
