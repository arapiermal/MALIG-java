package app.core.capability;

import java.util.*;
import java.util.function.BiFunction;

public final class ToolCapability implements Capability {
    private final ToolSchema schema;
    private final BiFunction<UUID, ToolCall, ToolResult> executor;
    public ToolCapability(ToolSchema schema, BiFunction<UUID, ToolCall, ToolResult> executor) {
        this.schema = Objects.requireNonNull(schema);
        this.executor = Objects.requireNonNull(executor);
    }
    @Override public ToolSchema schema() { return schema; }
    @Override public ToolResult execute(UUID agentId, ToolCall call) { return executor.apply(agentId, call); }
}
