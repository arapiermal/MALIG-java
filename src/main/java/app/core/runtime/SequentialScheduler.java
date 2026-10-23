package app.core.runtime;

import app.core.agent.AbstractAgent;
import app.core.environment.Goal;

import java.util.Collection;
import java.util.List;

public class SequentialScheduler implements Scheduler {
    @Override
    public void run(Collection<AbstractAgent> agents, List<Goal> goals) {
        for (AbstractAgent agent : agents) {
            agent.tick(goals);
        }
    }
}
