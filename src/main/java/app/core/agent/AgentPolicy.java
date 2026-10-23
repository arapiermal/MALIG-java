package app.core.agent;

import app.core.environment.Goal;
import app.core.planning.Plan;
import java.util.List;

/** Reacts to input/conditions and correlated results, proposing controlled work as a Plan.
 * A policy may reuse a Planner to compute steps; returning the same Plan format does not make
 * input/continuation selection and planning the same responsibility. No side effects during planning.
 */
@FunctionalInterface
public interface AgentPolicy {
    Plan createPlan(AbstractAgent agent, List<Goal> goals);
    default boolean supports(AbstractAgent agent, List<Goal> goals) { return true; }
}
