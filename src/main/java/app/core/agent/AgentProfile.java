package app.core.agent;

import java.util.*;

/** Descriptive identity and explicit reasoning instructions are separate, losslessly mapped fields. */
public record AgentProfile(String name, String role, String description, List<String> goals,
                           String systemPrompt, String instructions) {
    public AgentProfile {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        role = role == null ? "" : role;
        description = description == null ? "" : description;
        goals = goals == null ? List.of() : List.copyOf(goals);
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        instructions = instructions == null ? "" : instructions;
    }
    public AgentProfile(String name, String role, String description, List<String> goals) {
        this(name, role, description, goals, "", "");
    }
    public static AgentProfile named(String name) { return new AgentProfile(name, "", "", List.of()); }
}
