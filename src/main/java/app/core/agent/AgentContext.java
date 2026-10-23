package app.core.agent;

import app.core.capability.*;
import app.core.environment.*;
import app.core.memory.AgentMemory;
import app.core.message.*;
import app.core.planning.*;
import app.core.runtime.*;
import java.util.*;

public final class AgentContext {
    private final MessageBus messageBus;
    private final AgentMemory memory;
    private final CapabilityRegistry capabilities;
    private final EnvironmentView environment;
    private final Planner planner;
    private final ExecutionTrace trace;
    private AgentRuntime runtime;
    private UUID agentId;

    public AgentContext(MessageBus bus, AgentMemory memory, CapabilityRegistry capabilities,
                        EnvironmentView environment, Planner planner, ExecutionTrace trace) {
        this.messageBus = Objects.requireNonNull(bus);
        this.memory = Objects.requireNonNull(memory);
        this.capabilities = Objects.requireNonNull(capabilities);
        this.environment = Objects.requireNonNull(environment);
        this.planner = Objects.requireNonNull(planner);
        this.trace = Objects.requireNonNull(trace);
    }
    public void bind(AgentRuntime runtime, UUID agentId) {
        if (this.runtime != null && (this.runtime != runtime || !this.agentId.equals(agentId)))
            throw new IllegalStateException("Context already owned by another agent/runtime");
        if (runtime.trace() != trace || runtime.messageBus() != messageBus || runtime.environment() != environment)
            throw new IllegalArgumentException("Agent must share its runtime's trace and message bus");
        this.runtime = runtime;
        this.agentId = agentId;
    }
    public ToolResult invoke(ToolCall call, String plan, String step) {
        if (runtime == null) throw new IllegalStateException("Agent must be registered before execution");
        return runtime.invoke(agentId, call, plan, step);
    }
    public void send(AgentMessage message) {
        if (runtime == null || !agentId.equals(message.senderId())) throw new IllegalStateException("Invalid sender");
        runtime.send(message);
    }
    public boolean conversationActive(UUID id) { return runtime != null && runtime.conversation(id).active(); }
    public boolean invocationActive() { return runtime != null && runtime.invocationActive(agentId); }
    public Map<String, Object> organization() { return runtime == null ? Map.of() : runtime.organizationFor(agentId); }
    public List<AgentRequest> requests() { return runtime == null ? List.of() : runtime.requestsFor(agentId); }
    public UUID resolvePeer(String nameOrId) { if (runtime == null) throw new IllegalStateException("Agent not registered"); return runtime.resolveAgent(nameOrId); }
    public AgentMemory memory() { return memory; }
    public CapabilityRegistry capabilities() { return capabilities; }
    public EnvironmentView environment() { return environment; }
    public Planner planner() { return planner; }
    public ExecutionTrace trace() { return trace; }
}
