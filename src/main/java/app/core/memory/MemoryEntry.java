package app.core.memory;

import app.core.message.AgentMessage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MemoryEntry(
        UUID id,
        String type,
        String source,
        String content,
        Instant createdAt,
        Map<String, String> metadata
) {
    public MemoryEntry {
        id = id == null ? UUID.randomUUID() : id;
        type = type == null ? "note" : type;
        source = source == null ? "" : source;
        content = content == null ? "" : content;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static MemoryEntry observation(String source, String content, Instant createdAt) {
        return new MemoryEntry(null, "observation", source, content, createdAt, Map.of());
    }

    public static MemoryEntry reasoning(String source, String content, Instant createdAt) {
        return new MemoryEntry(null, "reasoning", source, content, createdAt, Map.of());
    }

    public static MemoryEntry message(AgentMessage message, Instant createdAt) {
        Map<String, String> metadata = new java.util.LinkedHashMap<>(message.metadata());
        metadata.put("messageId", message.id().toString()); metadata.put("type", message.type().name());
        metadata.put("senderId", String.valueOf(message.senderId())); metadata.put("recipientId", String.valueOf(message.recipientId()));
        return new MemoryEntry(message.id(), "message", String.valueOf(message.senderId()), message.content(), createdAt, metadata);
    }
}
