package app.core.capability;

import app.core.message.AgentMessage;
import java.util.*;
import java.util.function.Consumer;

public final class SendMessageCapability implements Capability {
    private final Consumer<AgentMessage> sender;
    public SendMessageCapability(Consumer<AgentMessage> sender) { this.sender = sender; }
    @Override public ToolSchema schema() {
        return ToolSchema.of("send_message", "Send a command to a registered agent.",
                Map.of("recipientId", "string", "content", "string"));
    }
    @Override public ToolResult execute(UUID agentId, ToolCall call) {
        UUID recipient = UUID.fromString((String) call.arguments().get("recipientId"));
        sender.accept(AgentMessage.direct(agentId, recipient, (String) call.arguments().get("content")));
        return ToolResult.success("Message queued for " + recipient, Map.of("recipientId", recipient));
    }
}
