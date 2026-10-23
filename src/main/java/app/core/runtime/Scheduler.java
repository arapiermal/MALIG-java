package app.core.runtime;

import app.core.agent.AbstractAgent;
import app.core.environment.Goal;

import java.util.Collection;
import java.util.List;

public interface Scheduler {
    void run(Collection<AbstractAgent> agents, List<Goal> goals);
}
