package app.core.agent;

import app.core.capability.*;
import app.core.environment.*;
import app.core.memory.*;
import app.core.planning.*;
import app.core.runtime.AgentRuntime;
import app.llm.LLMService;
import app.llm.LLMConfig;
import java.util.*;

/** Canonical assembly shared by headless scenarios and JavaFX. */
public final class AgentFactory {
    private final AgentRuntime runtime;
    private final java.util.function.Function<LLMConfig, LLMService> services;
    private final WorkspaceFiles workspaceFiles;
    private final PolicyRegistry policies = new PolicyRegistry();
    public PolicyRegistry policies() { return policies; }
    public AgentRuntime runtime() { return runtime; }
    private final Map<String, Planner> scenarioPlanners = new LinkedHashMap<>();
    private final Map<String, Capability> scenarioTools = new LinkedHashMap<>();
    public AgentFactory(AgentRuntime runtime, java.util.function.Function<LLMConfig, LLMService> services) {
        this(runtime, services, WorkspaceFiles.defaultRoot());
    }
    public AgentFactory(AgentRuntime runtime, java.util.function.Function<LLMConfig, LLMService> services, java.nio.file.Path workspaceRoot) {
        this.runtime = Objects.requireNonNull(runtime); this.services = Objects.requireNonNull(services);
        this.workspaceFiles = new WorkspaceFiles(workspaceRoot);
    }
    public AgentFactory planner(String name, Planner planner) { scenarioPlanners.put(name, planner); return this; }
    public AgentFactory tool(Capability capability) { scenarioTools.put(capability.name(), capability); return this; }
    public List<String> plannerNames() {
        List<String> names = new ArrayList<>(List.of("auto", "rules"));
        names.addAll(scenarioPlanners.keySet()); names.add("llm"); return List.copyOf(names);
    }
    public Set<String> toolNames() {
        Set<String> names = new LinkedHashSet<>(List.of("inspect_environment", "remember", "retrieve_memory", "send_message", "delegate_task", "request_agent", "get_request_result", "list_agents", "await_agent_requests"));
        names.addAll(WorkspaceFiles.NAMES);
        names.addAll(scenarioTools.keySet());
        runtime.environmentActions().forEach(s -> names.add(s.name()));
        return Collections.unmodifiableSet(names);
    }
    public AbstractAgent create(UUID id, AgentProfile profile, AgentConfiguration config, AgentMemory previousMemory) {
        if (config.type() == AgentType.PLANNER && !plannerNames().contains(config.planner())) throw new IllegalArgumentException("Unknown planner: " + config.planner());
        if (!toolNames().containsAll(config.tools())) throw new IllegalArgumentException("Unknown tool grant");
        AgentMemory memory = previousMemory == null
                ? new ModularMemory(config.memoryLimit(), config.contextMemory(), config.episodicMemory()) : previousMemory;
        boolean usesLlm = config.type() == AgentType.LLM || config.type() == AgentType.HYBRID
                || config.type() == AgentType.PLANNER && Set.of("auto", "llm").contains(config.planner());
        // Lazily resolve for planner/composite fallback: deterministic work never needs credentials.
        LLMService service = usesLlm ? new LLMService() {
            public LLMConfig config() { return config.llm(); }
            public app.llm.LLMOutput generate(app.llm.LLMInput input) {
                LLMService resolved = services.apply(config.llm());
                var output = resolved.generate(input);
                Map<String, String> metadata = new LinkedHashMap<>(output.metadata());
                metadata.put("provider", resolved.config().provider()); metadata.put("model", resolved.config().model());
                metadata.put("credentialSource", resolved.config().options().getOrDefault("credentialSource", ""));
                return new app.llm.LLMOutput(output.text(), output.toolCalls(), metadata);
            }
        } : null;
        PlannerRegistry planners = new PlannerRegistry(config.planner()).register("rules", new RuleBasedPlanner());
        scenarioPlanners.forEach(planners::register);
        if (service != null) planners.register("llm", new LLMPlanner(service));
        List<Capability> available = new ArrayList<>(List.of(new InspectEnvironmentCapability(runtime.environment()),
                new RememberCapability(memory), new SendMessageCapability(runtime::send)));
        available.addAll(workspaceFiles.capabilities());
        available.add(new ToolCapability(ToolSchema.of("retrieve_memory", "Retrieve relevant past entries.",
                Map.of("query", "string", "limit", "integer")), (actor, call) -> {
            double requestedLimit = ((Number) call.arguments().get("limit")).doubleValue();
            if (requestedLimit < 1 || requestedLimit > 20) return ToolResult.failure("Retrieval limit must be 1..20");
            int limit = (int) requestedLimit;
            if (limit < 1 || limit > 20) return ToolResult.failure("Retrieval limit must be 1..20");
            var entries = memory.search((String) call.arguments().get("query"), limit);
            return ToolResult.success("Retrieved " + entries.size() + " entries", Map.of("entries", entries));
        }));
        available.add(new ToolCapability(ToolSchema.of("delegate_task", "Delegate a goal to a direct child agent.",
                Map.of("childId", "string", "goal", "string")), (actor, call) -> {
            UUID task = runtime.delegate(actor, UUID.fromString((String) call.arguments().get("childId")),
                    Goal.of((String) call.arguments().get("goal")));
            return ToolResult.success("Delegated task " + task, Map.of("delegationId", task));
        }));
        available.add(new ToolCapability(ToolSchema.of("request_agent",
                "Request work from a peer using recipientId = exact unique name or UUID (discover with list_agents). Returns a pending request ID; provide onResult for later continuation. Do not poll repeatedly.",
                Map.of("recipientId", "string", "task", "string", "onResult", "string"), Set.of("recipientId", "task")),
                (actor, call) -> {
                  try {
                    UUID request = runtime.request(actor, runtime.resolveAgent((String) call.arguments().get("recipientId")),
                            Goal.of((String) call.arguments().get("task")), (String) call.arguments().getOrDefault("onResult", ""));
                    return ToolResult.success("Peer request queued: " + request, Map.of("requestId", request, "status", "PENDING"));
                  } catch (IllegalArgumentException ex) { return ToolResult.failure(ex.getMessage()); }
                }));
        available.add(new ToolCapability(ToolSchema.of("get_request_result", "Inspect a request in which this agent participates.",
                Map.of("requestId", "string")), (actor, call) -> {
                    var request = runtime.requestResult(actor, UUID.fromString((String) call.arguments().get("requestId")));
                    return ToolResult.success(request.outcomeLabel() + " (invocation status " + request.status() + "): " + request.result(), Map.of("request", request));
                }));
        available.add(new ToolCapability(new ToolSchema("await_agent_requests",
                "Wait for ALL named own peer requests to deliver terminal results, then invoke this agent once with every distinct result (including failures). No polling. Create requests WITHOUT onResult. Returns a wait ID, not completed work. A request can belong to only one wait set. Finish this invocation with a waiting acknowledgement, not a final answer.",
                Map.of("type", "object", "properties", Map.of(
                        "requestIds", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1, "maxItems", 16, "uniqueItems", true),
                        "onComplete", Map.of("type", "string")),
                        "required", List.of("requestIds", "onComplete"), "additionalProperties", false)), (actor, call) -> {
            try {
                var ids = ((List<?>) call.arguments().get("requestIds")).stream().map(value -> UUID.fromString((String) value)).toList();
                UUID wait = runtime.awaitRequests(actor, ids, (String) call.arguments().get("onComplete"));
                return ToolResult.success("Waiting for terminal peer results; no aggregation has run yet", Map.of("waitId", wait, "status", "WAITING", "requestIds", ids));
            } catch (IllegalArgumentException ex) { return ToolResult.failure(ex.getMessage()); }
        }));
        runtime.environmentActions().forEach(s -> available.add(runtime.environmentCapability(s)));
        available.add(new ToolCapability(ToolSchema.of("list_agents", "Discover registered peers, controller/policy configuration, task descriptions, tool grants and schemas. A capability is not itself an agent.", Map.of()),
                (actor, call) -> ToolResult.success("Registered agent directory", Map.of("agents", runtime.directory()))));
        available.addAll(scenarioTools.values());
        CapabilityRegistry capabilities = new CapabilityRegistry();
        available.stream().filter(c -> config.tools().contains(c.name())).forEach(capabilities::register);
        AgentContext context = new AgentContext(runtime.messageBus(), memory, capabilities, runtime.environment(), planners, runtime.trace());
        AgentPolicy policy = config.type() == AgentType.BASIC || config.type() == AgentType.HYBRID
                ? policies.resolve(config.policy(), config.policyOptions()) : null;
        AbstractAgent agent = switch (config.type()) {
            case BASIC -> new BasicAgent(id, profile, context, policy);
            case PLANNER -> new PlannerAgent(id, profile, context);
            case HYBRID -> new HybridAgent(id, profile, context, policy, service);
            case LLM -> new LLMAgent(id, profile, context, service);
            case HUMAN -> new HumanAgent(id, profile, context);
        };
        agent.configure(config);
        // Observability is committed during registration/replacement, not while editing shared memory.
        agent.setLlmStatus(app.llm.LLMStatus.configured(usesLlm ? config.llm() : null));

        return agent;
    }
}
