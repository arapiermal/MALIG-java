package app.core.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentMessage(
        UUID id,
        UUID senderId,
        UUID recipientId,
        String topic,
        MessageType type,
        MessagePriority priority,
        String content,
        Instant createdAt,
        Map<String, String> metadata
) {
    public AgentMessage {
        id = id == null ? UUID.randomUUID() : id;
        type = type == null ? MessageType.EVENT : type;
        priority = priority == null ? MessagePriority.NORMAL : priority;
        content = content == null ? "" : content;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentMessage direct(UUID senderId, UUID recipientId, String content) {
        return new AgentMessage(null, senderId, recipientId, null, MessageType.COMMAND,
                MessagePriority.NORMAL, content, null, Map.of());
    }

    public static AgentMessage topic(UUID senderId, String topic, String content) {
        return new AgentMessage(null, senderId, null, topic, MessageType.EVENT,
                MessagePriority.NORMAL, content, null, Map.of());
    }
}
