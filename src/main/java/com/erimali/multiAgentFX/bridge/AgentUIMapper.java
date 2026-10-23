package com.erimali.multiAgentFX.bridge;
import app.core.agent.*;
import app.llm.LLMConfig;
import com.erimali.multiAgentFX.model.AgentConfig;
import java.util.*;

public final class AgentUIMapper {
    public AgentConfig fromAgent(AbstractAgent a) {
        var config = a.configuration();
        AgentConfig c = new AgentConfig(a.id()); c.setName(a.name()); c.setRole(a.profile().role());
        c.setSystemPrompt(a.profile().systemPrompt()); c.setInstructions(a.profile().instructions());
        c.setDescription(a.profile().description()); c.setType(config.type()); c.setPlanner(config.planner());
        c.setPolicy(config.policy()); c.setPolicyOptions(config.policyOptions()); c.setTools(config.tools());
        c.setMemoryLimit(config.memoryLimit()); c.setShortMemoryEnabled(config.contextMemory()); c.setLongMemoryEnabled(config.episodicMemory());
        c.setMaxTokens(config.llm().maxTokens());
        c.setLlmOptions(config.llm().options());
        c.setProvider(config.llm().provider()); c.setModel(config.llm().model()); c.setTemperature(config.llm().temperature());
        return c;
    }
    public AgentProfile toProfile(AgentConfig c) {
        return new AgentProfile(c.getName().strip(), c.getRole(), c.getDescription(), List.of(),
                c.getSystemPrompt(), c.getInstructions());
    }
    public AgentConfiguration toConfiguration(AgentConfig c) {
        return new AgentConfiguration(c.getType(), c.getPlanner(), c.getMemoryLimit(),
                c.isShortMemoryEnabled(), c.isLongMemoryEnabled(), c.getTools(),
                new LLMConfig(c.getProvider(), c.getModel(), c.getTemperature(), c.getMaxTokens(), c.getLlmOptions()), c.getPolicy(), c.getPolicyOptions());
    }
}
