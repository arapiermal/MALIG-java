package app.core.environment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorldStateSnapshot(Instant capturedAt, List<WorldEntity> entities, Map<String, Object> facts) {
    public WorldStateSnapshot {
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        entities = entities == null ? List.of() : List.copyOf(entities);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public static WorldStateSnapshot empty() {
        return new WorldStateSnapshot(null, List.of(), Map.of());
    }

    public String summary() {
        return "entities=" + entities.size() + ", facts=" + facts;
    }
}
