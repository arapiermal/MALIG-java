package app.core.planning;

import app.core.agent.*;
import app.core.capability.*;
import app.core.runtime.*;
import java.util.*;

public final class PlanExecutor {
    public PlanExecution executePlan(Plan plan, AbstractAgent agent, AgentContext context) {
        context.trace().publish(ExecutionEvent.of(ExecutionEventType.PLAN_CREATED, agent.id(), "planner",
                plan.name() + " with " + plan.steps().size() + " steps"));
        List<PlanExecution.StepResult> results = new ArrayList<>();
        agent.recordExecution(new PlanExecution(plan, PlanExecution.Status.RUNNING, results));
        boolean success = !plan.steps().isEmpty() || plan.decision() != null;
        if (plan.decision() != null) context.trace().publish(new ExecutionEvent(null, ExecutionEventType.DECISION_MADE,
                agent.id(), "behavior", plan.decision().kind() + ": " + plan.decision().reason(),
                Map.of("decision", plan.decision().data(), "phase", "PROPOSED", "plan", plan.name())));
        for (PlanStep step : plan.steps()) {
            context.trace().publish(ExecutionEvent.of(ExecutionEventType.PLAN_STEP_STARTED, agent.id(), "plan", step.description()));
            ToolResult result = step.action() == null ? ToolResult.failure("Missing proposed action")
                    : step.action().agentId() != null && !agent.id().equals(step.action().agentId())
                    ? ToolResult.failure("Plan cannot impersonate another agent")
                    : context.invoke(new ToolCall(step.action().name(), step.action().parameters()), plan.name(), step.id());
            results.add(new PlanExecution.StepResult(step.id(), result));
            agent.recordExecution(new PlanExecution(plan, PlanExecution.Status.RUNNING, results));
            if (!result.success()) { success = false; break; }
        }
        PlanExecution execution = new PlanExecution(plan,
                success ? PlanExecution.Status.SUCCEEDED : PlanExecution.Status.FAILED, results);
        agent.recordExecution(execution);
        if (success && plan.decision() != null) {
            agent.publishResult("decision", plan.decision().data());
            agent.memory().putState("lastDecision", plan.decision().data());
        }
        context.trace().publish(ExecutionEvent.of(ExecutionEventType.PLAN_COMPLETED, agent.id(), "planner", execution.summary()));
        return execution;
    }
    public String execute(Plan plan, AbstractAgent agent, AgentContext context) { return executePlan(plan, agent, context).summary(); }
}
