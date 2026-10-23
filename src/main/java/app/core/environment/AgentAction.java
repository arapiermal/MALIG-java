package app.core.environment;

import java.util.Map;
import java.util.UUID;

public record AgentAction(UUID agentId, String name, Map<String, Object> parameters) {
    public AgentAction {
        name = name == null ? "" : name;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
