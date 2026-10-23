package app.scenario.synthetic;

import app.core.agent.*;
import app.core.capability.*;
import app.core.environment.*;
import app.core.planning.*;
import app.core.runtime.*;
import app.llm.LLMConfig;
import java.util.*;

public final class ResourceScenario implements ScenarioBootstrap {
    @Override public String name() { return "Synthetic resource scenario"; }
    @Override public EnvironmentContext createEnvironment() { return new ResourceEnvironment(); }
    public static int shortage(int level, int target) { return target - level; }
    public static AgentFactory factory(AgentRuntime runtime) {
        AgentFactory factory = new AgentFactory(runtime, app.llm.provider.LLMServiceFactory::forConfig);
        new ResourceScenario().configure(factory); return factory;
    }
    @Override public void configure(AgentFactory factory) {
        RegionalCoordination.configure(factory);
        factory.planner("resources", new ResourcePlanner());
        factory.tool(new ToolCapability(ToolSchema.of("recommend_resources",
                "Publish an advisory decision for this work request. This does not mutate resources. Non-intervention is allowed.",
                Map.of("intervene", "boolean", "reason", "string")), (id, call) -> {
            AbstractAgent agent = factory.runtime().find(id).orElseThrow();
            if (agent.resultData().containsKey("resourceRecommendation"))
                return ToolResult.failure("A recommendation has already been published for this invocation");
            Map<String, Object> recommendation = Map.of("intervene", call.arguments().get("intervene"),
                    "reason", call.arguments().get("reason"));
            agent.publishResult("resourceRecommendation", recommendation);
            return ToolResult.success("Recommendation published", recommendation);
        }));
        factory.tool(new ToolCapability(ToolSchema.of("evaluate_resources", "Calculate the difference from a resource target.",
                Map.of("target", "integer")), (id, call) -> {
            double target = ((Number) call.arguments().get("target")).doubleValue();
            if (target < 0 || target > 100) return ToolResult.failure("Target must be within 0..100");
            int level = ((Number) factory.runtime().environment().snapshot().facts().get("resourceLevel")).intValue();
            int difference = shortage(level, (int) target);
            return ToolResult.success("Resource adjustment needed: " + difference, Map.of("adjustment", difference));
        }));
        factory.policies().register("resources", "Algorithmic resource restoration to a configured target.",
                Map.of("target", "80"), options -> {
                    ResourcePlanner planner = new ResourcePlanner(level(options.get("target")));
                    return new AgentPolicy() {
                        public boolean supports(AbstractAgent a, List<Goal> goals) { return planner.supports(a, goals, a.context()); }
                        public Plan createPlan(AbstractAgent a, List<Goal> goals) { return planner.createPlan(a, goals, a.context()); }
                    };
                });
        factory.policies().register("resource-monitor", "Observe; request peer advice; act only on an explicit intervention recommendation.",
                Map.of("threshold", "80", "target", "80", "analysisAgentId", "", "plannerAgentId", ""), options -> {
                    int threshold = level(options.get("threshold"));
                    int target = level(options.get("target"));
                    UUID analyst = peer(options.get("analysisAgentId")), planner = peer(options.get("plannerAgentId"));
                    return new AgentPolicy() {
                        public boolean supports(AbstractAgent a, List<Goal> goals) {
                            return goals.stream().allMatch(g -> Set.of("monitor resources", "analysis ready", "resource work complete")
                                    .contains(g.description().toLowerCase(Locale.ROOT)));
                        }
                        public Plan createPlan(AbstractAgent a, List<Goal> goals) {
                            String task = goals.getFirst().description().toLowerCase(Locale.ROOT);
                            if (task.equals("monitor resources")) {
                                int current = ((Number) a.context().environment().snapshot().facts().get("resourceLevel")).intValue();
                                List<PlanStep> steps = new ArrayList<>();
                                steps.add(step(a, "observe", "inspect_environment", Map.of()));
                                if (current < threshold) steps.add(step(a, "analysis", "request_agent", Map.of(
                                        "recipientId", analyst.toString(), "task", "Assess whether to restore resources to " + target + ". Call recommend_resources with your decision and a reason; non-intervention is allowed. Then summarize.",
                                        "onResult", "analysis ready")));
                                return new Plan("resource-monitor", steps);
                            }
                            UUID recipient = task.equals("analysis ready") ? analyst : planner;
                            UUID requestId = a.activeMessage().map(m -> m.metadata().get("requestId"))
                                    .map(UUID::fromString).orElseThrow(() -> new IllegalStateException("Continuation requires a correlated response"));
                            AgentRequest result = a.context().requests().stream()
                                    .filter(r -> r.id().equals(requestId) && r.requesterId().equals(a.id()) && r.recipientId().equals(recipient))
                                    .findFirst().orElseThrow();
                            if (result.status() != AgentRequest.Status.SUCCEEDED || task.equals("resource work complete"))
                                return new Plan("record-peer-result", List.of(step(a, "record", "remember", Map.of(
                                        "content", "Peer request " + result.id() + " " + result.status() + ": " + result.result()))));
                            Object recommendation = result.outputs().get("resourceRecommendation");
                            if (!(recommendation instanceof Map<?, ?> data) || !Boolean.TRUE.equals(data.get("intervene")))
                                return new Plan("no-intervention", List.of(step(a, "record", "remember", Map.of(
                                        "content", "No intervention for request " + result.id() + ": " +
                                                (recommendation == null ? "No validated recommendation was supplied" : recommendation)))));
                            return new Plan("request-resource-action", List.of(step(a, "action", "request_agent", Map.of(
                                    "recipientId", planner.toString(), "task", "Restore resources to the configured target",
                                    "onResult", "resource work complete"))));
                        }
                    };
                });
    }
    private static int level(String value) {
        int result;
        try { result = Integer.parseInt(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Resource target/threshold must be an integer"); }
        if (result < 0 || result > 100) throw new IllegalArgumentException("Resource target/threshold must be 0..100");
        return result;
    }
    private static UUID peer(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Select valid analysisAgentId and plannerAgentId peer UUIDs"); }
    }
    private static PlanStep step(AbstractAgent a, String id, String tool, Map<String, Object> arguments) {
        return new PlanStep(id, tool, new AgentAction(a.id(), tool, arguments));
    }
    public static AgentConfiguration configuration(AgentType type, AgentFactory factory) {
        return new AgentConfiguration(type, "auto", 50, true, true, factory.toolNames(),
                new LLMConfig("offline", "offline-rules", 0, 1024, Map.of()));
    }
    @Override public List<AbstractAgent> loadDemo(AgentRuntime runtime, AgentFactory factory, LLMConfig analysisProvider) {
        return createDemo(runtime, factory, analysisProvider, 80, 80);
    }
    public List<AbstractAgent> loadDemo(AgentRuntime runtime, AgentFactory factory, LLMConfig provider, int initial, int target, int threshold) {
        level(Integer.toString(initial)); level(Integer.toString(target)); level(Integer.toString(threshold));
        if (!(runtime.environment() instanceof ResourceEnvironment environment)) throw new IllegalArgumentException("Requires the resource environment");
        var before = environment.snapshot();
        environment.configureDemo(initial, target);
        runtime.trace().publish(new ExecutionEvent(null, ExecutionEventType.ENVIRONMENT_ACTION, null, "scenario-setup",
                "User configured resource demo: initial=" + initial + ", target=" + target + ", threshold=" + threshold,
                Map.of("before", before, "after", environment.snapshot(), "provider", provider.provider())));
        return createDemo(runtime, factory, provider, target, threshold);
    }
    private List<AbstractAgent> createDemo(AgentRuntime runtime, AgentFactory factory, LLMConfig analysisProvider, int target, int threshold) {
        factory.planner("resources", new ResourcePlanner(target));
        UUID analystId = UUID.randomUUID(), plannerId = UUID.randomUUID();
        boolean offline = app.llm.provider.LLMServiceFactory.isOffline(analysisProvider.provider());
        AbstractAgent analyst = factory.create(analystId, new AgentProfile(
                offline ? "Analysis [offline test]" : "Analysis [Google Gemini]", "analysis",
                "Resource interpretation", List.of(),
                "Analyze the observed resource condition using inspect_environment and evaluate_resources. Then call recommend_resources exactly once with intervene true or false and a reason. "
                        + "You may recommend non-intervention when justified. Do not claim to change resources. After publishing the recommendation, summarize without further tools.", ""),
                new AgentConfiguration(AgentType.LLM, "auto", 50, true, true,
                        Set.of("inspect_environment", "evaluate_resources", "recommend_resources"), analysisProvider), null);
        LLMConfig unused = new LLMConfig("offline", "offline-rules", 0, 1024, Map.of());
        AbstractAgent planner = factory.create(plannerId, new AgentProfile("Resource planner", "action", "", List.of()),
                new AgentConfiguration(AgentType.PLANNER, "resources", 50, true, true,
                        Set.of("inspect_environment", "evaluate_resources", "adjust_resources"), unused), null);
        AbstractAgent monitor = factory.create(UUID.randomUUID(), new AgentProfile("Deterministic monitor", "monitor", "", List.of()),
                new AgentConfiguration(AgentType.BASIC, "auto", 50, true, true,
                        Set.of("inspect_environment", "remember", "request_agent", "get_request_result"), unused,
                        "resource-monitor", Map.of("threshold", Integer.toString(threshold), "target", Integer.toString(target), "analysisAgentId", analystId.toString(), "plannerAgentId", plannerId.toString())), null);
        List<AbstractAgent> agents = List.of(monitor, analyst, planner);
        agents.forEach(runtime::register);
        runtime.submitGoal(monitor.id(), Goal.of("monitor resources"));
        return agents;
    }
}
