package app.core.planning;

import java.util.*;

/** A proposed behavior decision, distinct from the later execution outcome. */
public record Decision(Kind kind, String reason) {
    public enum Kind { ACCEPT, REJECT, DEFER, NO_ACTION }
    public Decision { Objects.requireNonNull(kind); if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Explain the decision"); }
    public Map<String, Object> data() { return Map.of("kind", kind.name(), "reason", reason); }
    /** Strict contract for a published peer decision; a success flag or prose is not a decision. */
    public static Optional<Decision> from(Object value) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("kind") instanceof String kind) || !(map.get("reason") instanceof String reason)) return Optional.empty();
        try { return Optional.of(new Decision(Kind.valueOf(kind), reason)); }
        catch (IllegalArgumentException ex) { return Optional.empty(); }
    }
}
