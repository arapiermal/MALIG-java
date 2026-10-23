package com.erimali.multiAgentFX.model;

import app.core.agent.AgentType;
import java.util.*;

/** Editable UI draft only. Status, memory, plans, and logs always come from runtime objects. */
public final class AgentConfig {
    private final UUID id;
    private String name = "";
    private String role = "";
    private AgentType type = AgentType.BASIC;
    private String description = "";
    private boolean enabled = true;
    private String planner = "auto";
    private String policy = "rules";
    private Map<String, String> policyOptions = Map.of();
    public String getPolicy() { return policy; } public void setPolicy(String value) { policy = value; }
    public Map<String, String> getPolicyOptions() { return policyOptions; }
    public void setPolicyOptions(Map<String, String> value) { policyOptions = Map.copyOf(value); }
    private final app.llm.LLMConfig defaults = app.llm.provider.LLMServiceFactory.defaults();
    private String provider = defaults.provider();
    private String model = defaults.model();
    private double temperature = defaults.temperature();
    private int maxTokens = defaults.maxTokens();
    private Map<String, String> llmOptions = defaults.options();
    public Map<String, String> getLlmOptions() { return llmOptions; }
    public void setLlmOptions(Map<String, String> value) { llmOptions = Map.copyOf(value); }
    public boolean isProviderTemperature() { return "provider-default".equals(llmOptions.get(app.llm.LLMConfig.TEMPERATURE_MODE)); }
    public void setProviderTemperature(boolean value) {
        Map<String, String> options = new LinkedHashMap<>(llmOptions);
        options.put(app.llm.LLMConfig.TEMPERATURE_MODE, value ? "provider-default" : "explicit"); llmOptions = Map.copyOf(options);
    }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int value) { maxTokens = value; }
    private String systemPrompt = "";
    private String instructions = "";
    private Set<String> tools = new LinkedHashSet<>(List.of("inspect_environment", "remember", "retrieve_memory", "send_message", "delegate_task"));
    private boolean shortMemoryEnabled = true;
    private boolean longMemoryEnabled = true;
    private int memoryLimit = 50;
    public AgentConfig() { this(UUID.randomUUID()); }
    public AgentConfig(UUID id) { this.id = id; }
    public AgentConfig copy() {
        AgentConfig c = new AgentConfig(id);
        c.name = name; c.role = role; c.type = type; c.description = description; c.enabled = enabled;
        c.policy = policy; c.policyOptions = Map.copyOf(policyOptions);
        c.planner = planner; c.provider = provider; c.model = model; c.temperature = temperature;
        c.maxTokens = maxTokens;
        c.llmOptions = Map.copyOf(llmOptions);
        c.systemPrompt = systemPrompt; c.instructions = instructions; c.tools = new LinkedHashSet<>(tools);
        c.shortMemoryEnabled = shortMemoryEnabled; c.longMemoryEnabled = longMemoryEnabled; c.memoryLimit = memoryLimit;
        return c;
    }
    public UUID getId() { return id; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getRole() { return role; } public void setRole(String v) { role = v; }
    public AgentType getType() { return type; } public void setType(AgentType v) { type = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public String getPlanner() { return planner; } public void setPlanner(String v) { planner = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getModel() { return model; } public void setModel(String v) { model = v; }
    public double getTemperature() { return temperature; } public void setTemperature(double v) { temperature = v; }
    public String getSystemPrompt() { return systemPrompt; } public void setSystemPrompt(String v) { systemPrompt = v; }
    public String getInstructions() { return instructions; } public void setInstructions(String v) { instructions = v; }
    public Set<String> getTools() { return Set.copyOf(tools); } public void setTools(Set<String> v) { tools = new LinkedHashSet<>(v); }
    public boolean isShortMemoryEnabled() { return shortMemoryEnabled; } public void setShortMemoryEnabled(boolean v) { shortMemoryEnabled = v; }
    public boolean isLongMemoryEnabled() { return longMemoryEnabled; } public void setLongMemoryEnabled(boolean v) { longMemoryEnabled = v; }
    public int getMemoryLimit() { return memoryLimit; } public void setMemoryLimit(int v) { memoryLimit = v; }
    @Override public String toString() { return (name.isBlank() ? "Unnamed Agent" : name) + " (" + type + ")"; }
}
