package app.core.capability;

import java.util.UUID;

/** A tool is an explicitly registered capability, never an arbitrary reflected method. */
public interface Capability {
    ToolSchema schema();
    default String name() { return schema().name(); }
    default String description() { return schema().description(); }
    ToolResult execute(UUID agentId, ToolCall call);
}
