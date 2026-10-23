package app.core.agent;
import app.core.environment.Goal;
import java.util.*;

public final class PlannerAgent extends AbstractAgent {
    public PlannerAgent(UUID id, AgentProfile profile, AgentContext context) { super(id, profile, context); }
    @Override protected void decide(List<Goal> goals) {
        complete(new app.core.planning.PlanExecutor().execute(context().planner().createPlan(this, goals, context()), this, context()));
    }
}
