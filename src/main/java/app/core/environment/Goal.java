package app.core.environment;

import java.util.UUID;

public record Goal(UUID id, String description, int priority) {
    public Goal {
        id = id == null ? UUID.randomUUID() : id;
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Goal description is required");
    }

    public static Goal of(String description) {
        return new Goal(null, description, 0);
    }
}
