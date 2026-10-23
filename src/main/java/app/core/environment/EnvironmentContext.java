package app.core.environment;

import app.core.capability.ToolSchema;
import java.util.List;

/** Scenario adapter. Only the runtime calls execute/advanceTick; each adapter validates atomically. */
public interface EnvironmentContext extends EnvironmentView {
    List<ToolSchema> actions();
    ActionResult validate(AgentAction action);
    ActionResult execute(AgentAction action);
    void advanceTick();

    static EnvironmentContext empty() {
        return new EnvironmentContext() {
            public WorldStateSnapshot snapshot() { return WorldStateSnapshot.empty(); }
            public List<ToolSchema> actions() { return List.of(); }
            public ActionResult validate(AgentAction action) { return ActionResult.failure("No actions supported"); }
            public ActionResult execute(AgentAction action) { return validate(action); }
            public void advanceTick() { }
        };
    }
}
