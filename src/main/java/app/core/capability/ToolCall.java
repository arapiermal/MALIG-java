package app.core.capability;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String name, Map<String, Object> arguments) {
    public ToolCall {
        Objects.requireNonNull(name);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
