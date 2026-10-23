package app.core.capability;

import app.core.environment.EnvironmentView;
import java.util.*;

public final class InspectEnvironmentCapability implements Capability {
    private final EnvironmentView environment;
    public InspectEnvironmentCapability(EnvironmentView environment) { this.environment = Objects.requireNonNull(environment); }
    @Override public ToolSchema schema() { return ToolSchema.of("inspect_environment", "Observe the environment.", Map.of()); }
    @Override public ToolResult execute(UUID agentId, ToolCall call) {
        var state = environment.snapshot();
        return ToolResult.success(state.summary(), Map.of("facts", state.facts(), "entities", state.entities()));
    }
}
