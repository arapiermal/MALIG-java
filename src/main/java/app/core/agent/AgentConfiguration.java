package app.core.agent;

import app.llm.LLMConfig;
import java.util.*;

/** Type selects a controller; policy and planner IDs configure behavior without new subclasses. */
public record AgentConfiguration(AgentType type, String planner, int memoryLimit,
        boolean contextMemory, boolean episodicMemory, Set<String> tools, LLMConfig llm,
        String policy, Map<String, String> policyOptions) {
    public AgentConfiguration {
        if (type == null || planner == null || memoryLimit < 1 || llm == null || policy == null)
            throw new IllegalArgumentException("Invalid agent configuration");
        tools = Set.copyOf(tools); policyOptions = Map.copyOf(policyOptions);
    }
    public AgentConfiguration(AgentType type, String planner, int memoryLimit, boolean contextMemory,
                              boolean episodicMemory, Set<String> tools, LLMConfig llm) {
        this(type, planner, memoryLimit, contextMemory, episodicMemory, tools, llm, "rules", Map.of());
    }
}
