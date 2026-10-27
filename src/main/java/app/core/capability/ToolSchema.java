package app.core.capability;

import java.util.*;

/** A deliberately small JSON-schema subset: typed primitive arguments and bounded string arrays; no extra keys. */
public record ToolSchema(String name, String description, Map<String, Object> parametersSchema) {
    public ToolSchema {
        Objects.requireNonNull(name);
        description = description == null ? "" : description;
        parametersSchema = parametersSchema == null ? Map.of() : Map.copyOf(parametersSchema);
    }

    public static ToolSchema of(String name, String description, Map<String, String> arguments) {
        return of(name, description, arguments, arguments.keySet());
    }

    public static ToolSchema of(String name, String description, Map<String, String> arguments, Set<String> required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        arguments.forEach((key, type) -> properties.put(key, Map.of("type", type)));
        return new ToolSchema(name, description, Map.of("type", "object", "properties", properties,
                "required", List.copyOf(required), "additionalProperties", false));
    }

    public Optional<String> validate(Map<String, Object> arguments) {
        Map<?, ?> properties = (Map<?, ?>) parametersSchema.getOrDefault("properties", Map.of());
        for (Object key : (List<?>) parametersSchema.getOrDefault("required", List.of())) {
            if (!arguments.containsKey(key)) return Optional.of("Missing argument: " + key);
        }
        for (var entry : arguments.entrySet()) {
            if (!properties.containsKey(entry.getKey())) return Optional.of("Unknown argument: " + entry.getKey());
            String type = String.valueOf(((Map<?, ?>) properties.get(entry.getKey())).get("type"));
            Object value = entry.getValue();
            boolean valid = switch (type) {
                case "string" -> value instanceof String text && (!text.isBlank()
                        || Integer.valueOf(0).equals(((Map<?, ?>) properties.get(entry.getKey())).get("minLength")));
                case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
                case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == Math.rint(n.doubleValue());
                case "boolean" -> value instanceof Boolean;
                case "array" -> validStringArray(value, (Map<?, ?>) properties.get(entry.getKey()));
                default -> false;
            };
            if (!valid) return Optional.of("Argument " + entry.getKey() + " must be " + type);
        }
        return Optional.empty();
    }
    private static boolean validStringArray(Object value, Map<?, ?> schema) {
        if (!(value instanceof List<?> items) || !(schema.get("items") instanceof Map<?, ?> itemSchema)
                || !"string".equals(itemSchema.get("type"))) return false;
        int min = schema.get("minItems") instanceof Number n ? n.intValue() : 0;
        int max = schema.get("maxItems") instanceof Number n ? n.intValue() : 16;
        return items.size() >= min && items.size() <= max && items.stream().allMatch(i -> i instanceof String s && !s.isBlank())
                && (!Boolean.TRUE.equals(schema.get("uniqueItems")) || new HashSet<>(items).size() == items.size());
    }
}
