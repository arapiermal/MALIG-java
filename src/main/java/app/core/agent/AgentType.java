package app.core.agent;

/** Controller presets; policies and planners provide extensible behavior within these presets. */
public enum AgentType {
    BASIC("Deterministic policy"), PLANNER("Planner"), HYBRID("Composite: classical then LLM"),
    LLM("LLM controller"), HUMAN("Human controller");
    private final String label;
    AgentType(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
