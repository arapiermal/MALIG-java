package app.core.environment;

import java.util.Map;
import java.util.UUID;

public record WorldEntity(UUID id, String type, String name, Map<String, Object> attributes) {
    public WorldEntity {
        id = id == null ? UUID.randomUUID() : id;
        type = type == null ? "entity" : type;
        name = name == null ? id.toString() : name;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
