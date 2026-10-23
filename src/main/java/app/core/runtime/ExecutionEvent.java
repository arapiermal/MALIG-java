package app.core.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExecutionEvent(
        Instant occurredAt,
        ExecutionEventType type,
        UUID agentId,
        String source,
        String message,
        Map<String, Object> metadata
) {
    public ExecutionEvent {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        source = source == null ? "runtime" : source;
        message = message == null ? "" : message;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ExecutionEvent of(ExecutionEventType type, UUID agentId, String source, String message) {
        return new ExecutionEvent(null, type, agentId, source, message, Map.of());
    }
}
