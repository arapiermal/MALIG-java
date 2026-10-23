package app.core.planning;

import app.core.agent.*;
import app.core.environment.*;
import app.core.runtime.*;
import app.llm.*;
import java.util.*;

/** Turns provider-neutral function-call proposals into a plan; execution remains controlled. */
public final class LLMPlanner implements Planner {
    private final LLMService service;
    public LLMPlanner(LLMService service) { this.service = Objects.requireNonNull(service); }
    @Override public boolean supports(AbstractAgent agent, List<Goal> goals, AgentContext context) { return !agent.capabilities().all().isEmpty(); }
    @Override public Plan createPlan(AbstractAgent agent, List<Goal> goals, AgentContext context) {
        LLMInput input = LLMReasoner.input(agent, goals, List.of("Return an ordered sequence of tool calls for this goal."));
        LLMOutput output = LLMReasoner.generate(agent, service, input);
        if (output.toolCalls().isEmpty()) throw new IllegalStateException("Planner returned no executable candidate steps: " + output.text());
        if (output.toolCalls().size() > 8) throw new IllegalStateException("Plan exceeds 8 steps");
        List<PlanStep> steps = new ArrayList<>();
        output.toolCalls().forEach(call -> steps.add(new PlanStep("step-" + (steps.size() + 1), call.name(),
                new AgentAction(agent.id(), call.name(), call.arguments()))));
        return new Plan("llm-proposed-plan", steps);
    }
}
