package app.core.environment;

import java.util.*;

/** Optional trusted-host configuration. Not part of EnvironmentView or an agent capability. Use via AgentRuntime. */
public interface EnvironmentManagement {
    enum FactType {
        INTEGER, NUMBER, BOOLEAN, STRING, OTHER;
        public Object normalize(Object value) {
            return switch (this) {
                case INTEGER -> {
                    if (!(value instanceof Number)) throw new IllegalArgumentException("An integer number is required");
                    try { yield new java.math.BigDecimal(value.toString()).longValueExact(); }
                    catch (NumberFormatException | ArithmeticException ex) { throw new IllegalArgumentException("A finite 64-bit integer is required"); }
                }
                case NUMBER -> {
                    if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("A finite number is required");
                    yield n.doubleValue();
                }
                case BOOLEAN -> { if (!(value instanceof Boolean)) throw new IllegalArgumentException("A boolean is required"); yield value; }
                case STRING -> {
                    if (!(value instanceof String s) || s.length() > 4096) throw new IllegalArgumentException("A string of at most 4096 characters is required");
                    yield s;
                }
                case OTHER -> throw new IllegalArgumentException("This fact type is read-only");
            };
        }
        public Object parse(String value) {
            try {
                return normalize(switch (this) {
                    case INTEGER -> Long.valueOf(value.strip()); case NUMBER -> Double.valueOf(value.strip());
                    case BOOLEAN -> { if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("Use true or false"); yield Boolean.valueOf(value); }
                    case STRING -> value; case OTHER -> throw new IllegalArgumentException("Read-only fact");
                });
            } catch (NumberFormatException ex) { throw new IllegalArgumentException(this == INTEGER ? "Enter a 64-bit integer" : "Enter a finite number"); }
        }
        public static FactType of(Object value) {
            if (value instanceof Boolean) return BOOLEAN;
            if (value instanceof String) return STRING;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return INTEGER;
            return value instanceof Number ? NUMBER : OTHER;
        }
    }
    record Fact(String name, FactType type, Object value, boolean editable, boolean deletable, String description) { }
    enum Operation { CREATE, UPDATE, DELETE }
    /** expectedValue is an optional optimistic guard for a previously inspected value. No null fact values. */
    record Change(Operation operation, String name, FactType type, Object value, Object expectedValue) {
        public Change { Objects.requireNonNull(operation); Objects.requireNonNull(name); }
        public Change(Operation operation, String name, FactType type, Object value) { this(operation, name, type, value, null); }
    }
    List<Fact> configurationFacts();
    /** Backend validates ownership, type, range and optional expected value before changing authoritative observations. */
    void applyHostChange(Change change);
}
