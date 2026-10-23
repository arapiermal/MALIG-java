package app.core.planning;
import app.core.agent.*;
import app.core.environment.Goal;
import java.util.List;

/** Generates candidate executable steps. Input reactions belong to policies; execution belongs to PlanExecutor/runtime. */
public interface Planner {
    Plan createPlan(AbstractAgent agent, List<Goal> goals, AgentContext context);
    default boolean supports(AbstractAgent agent, List<Goal> goals, AgentContext context) { return true; }
}
