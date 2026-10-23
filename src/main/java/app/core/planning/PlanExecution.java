package app.core.planning;
import app.core.capability.ToolResult;
import java.util.List;

public record PlanExecution(Plan plan, Status status, List<StepResult> steps) {
    public PlanExecution { steps = List.copyOf(steps); }
    public enum Status { PLANNED, RUNNING, SUCCEEDED, FAILED }
    public record StepResult(String stepId, ToolResult result) { }
    public String summary() {
        return status + ": " + (plan.decision() == null ? "" : plan.decision().kind() + " — " + plan.decision().reason() + "; ")
                + (steps.isEmpty() ? "No effects" : steps.getLast().result().message());
    }
}
