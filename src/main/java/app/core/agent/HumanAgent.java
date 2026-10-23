package app.core.agent;

import app.core.environment.*;
import app.core.planning.*;
import java.util.*;

/** Human proposals bind to a specific queued goal and use the common validated execution path. */
public final class HumanAgent extends AbstractAgent {
    private final Map<UUID, AgentAction> proposals = new LinkedHashMap<>();
    public HumanAgent(UUID id, AgentProfile profile, AgentContext context) { super(id, profile, context); }
    public synchronized boolean hasProposals() { return !proposals.isEmpty(); }
    @Override public synchronized void submitGoal(Goal goal) {
        super.submitGoal(goal);
        if (proposals.isEmpty() && isExecutable()) changeStatus(AgentStatus.WAITING);
    }
    public synchronized void propose(AgentAction action) {
        Goal goal = pendingGoals().stream().filter(g -> !proposals.containsKey(g.id()))
                .max(Comparator.comparingInt(Goal::priority)).orElse(null);
        if (goal == null) { goal = Goal.of("Execute human proposal"); submitGoal(goal); }
        proposals.put(goal.id(), Objects.requireNonNull(action));
    }
    @Override protected synchronized boolean canProcessGoal(Goal goal) { return proposals.containsKey(goal.id()); }
    @Override public synchronized void cancelGoal(UUID id) { super.cancelGoal(id); proposals.remove(id); }
    @Override protected synchronized void decide(List<Goal> goals) {
        AgentAction action = proposals.remove(goals.getFirst().id());
        if (action == null) throw new IllegalStateException("Awaiting a human proposal for this goal");
        var step = new PlanStep(UUID.randomUUID().toString(), "Human proposal", action);
        complete(new PlanExecutor().execute(new Plan("human", List.of(step)), this, context()));
    }
    @Override public synchronized void restoreFrom(AbstractAgent previous) {
        super.restoreFrom(previous);
        if (previous instanceof HumanAgent human) proposals.putAll(human.proposals);
    }
}
