package app.core.capability;

import app.core.memory.*;
import java.util.*;

public final class RememberCapability implements Capability {
    private final AgentMemory memory;
    public RememberCapability(AgentMemory memory) { this.memory = memory; }
    @Override public ToolSchema schema() { return ToolSchema.of("remember", "Retain useful information.", Map.of("content", "string")); }
    @Override public ToolResult execute(UUID agentId, ToolCall call) {
        String content = (String) call.arguments().get("content");
        memory.remember(new MemoryEntry(null, "note", "tool", content, null, Map.of()));
        return ToolResult.success("Remembered: " + content, Map.of("content", content));
    }
}
