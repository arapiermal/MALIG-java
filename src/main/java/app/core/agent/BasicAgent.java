package app.core.agent;

import app.core.environment.Goal;
import app.core.planning.Plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class BasicAgent extends AbstractAgent {
    private final AgentPolicy policy;

    public BasicAgent(AgentProfile profile, AgentContext context, AgentPolicy policy) {
        super(profile, context);
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public BasicAgent(UUID id, AgentProfile profile, AgentContext context, AgentPolicy policy) {
        super(id, profile, context);
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    protected void decide(List<Goal> goals) {
        if (!policy.supports(this, goals)) throw new app.core.runtime.TaskFailure("Selected deterministic policy does not support this task or its grants");
        Plan plan = policy.createPlan(this, goals);
        String result = new app.core.planning.PlanExecutor().execute(plan, this, context());
        complete(result);
    }


}
