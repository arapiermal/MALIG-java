package app.scenario.synthetic;

import app.core.agent.*;
import app.core.capability.*;
import app.core.environment.*;
import app.core.planning.*;
import app.core.runtime.*;
import app.llm.LLMConfig;
import java.util.*;

/** Regional behavior and algorithms plug into the existing scenario factory and execution pipeline. */
public final class RegionalCoordination {
    private RegionalCoordination() { }
    private static final String START = "Coordinate regional resources", OPTIONS = "Regional options ready", ADVICE = "Regional advice ready ";
    private static ResourceEnvironment environment(AgentFactory f) {
        if (!(f.runtime().environment() instanceof ResourceEnvironment e)) throw new IllegalStateException("Regional coordination requires the resource scenario"); return e;
    }
    private static Plan action(AbstractAgent a, String name, Map<String, Object> args) {
        Decision decision = switch (name) {
            case "request_agent" -> new Decision(Decision.Kind.DEFER, "Request peer work: " + args.get("task"));
            case "transfer_resources" -> new Decision(Decision.Kind.ACCEPT, "Apply the offered current feasible option chosen by the selector");
            default -> null;
        };
        return new Plan("regional-" + name, List.of(new PlanStep("operation", name, new AgentAction(a.id(), name, args))), decision);
    }
    private static Plan note(AbstractAgent a, String text) { return new Plan("regional-no-action", List.of(), new Decision(Decision.Kind.NO_ACTION, text)); }
    private static AgentRequest response(AbstractAgent a, UUID peer, String continuation) {
        UUID id = a.activeMessage().map(m -> m.metadata().get("requestId")).map(UUID::fromString)
                .orElseThrow(() -> new IllegalArgumentException("This phase requires a real correlated peer response"));
        return a.context().requests().stream().filter(r -> r.id().equals(id) && r.requesterId().equals(a.id())
                && r.recipientId().equals(peer) && r.onResult().equals(continuation)).findFirst().orElseThrow();
    }
    public static void configure(AgentFactory factory) {
        factory.runtime().resultContract("Calculate regional transfers", outputs -> {
            if (!(outputs.get("transferOptions") instanceof List<?> options)
                    || options.stream().anyMatch(o -> !(o instanceof Map<?, ?> m) || !(m.get("optionId") instanceof String)
                        || !(m.get("amount") instanceof Number)))
                return Optional.of("Expected structured transferOptions from calculate_transfers; ordinary text is not a calculation");
            if (!options.equals(environment(factory).transferOptions().stream().map(ResourceEnvironment.TransferOption::data).toList()))
                return Optional.of("transferOptions does not match the current deterministic calculation");
            return Optional.empty(); // Empty feasible set is a legitimate result, not a transfer authorization.
        });
        factory.tool(new ToolCapability(ToolSchema.of("calculate_transfers", "Compute feasible regional transfers using reserve floors, shortage and transport capacity. Publishes structured transferOptions; performs no mutation.", Map.of()), (id, call) -> {
            var options = environment(factory).transferOptions().stream().map(ResourceEnvironment.TransferOption::data).toList();
            factory.runtime().find(id).orElseThrow().publishResult("transferOptions", options);
            return ToolResult.success("Feasible transfer options: " + options, Map.of("options", options));
        }));
        factory.tool(new ToolCapability(ToolSchema.of("choose_transfer", "Publish advice: an offered optionId or NONE, with a reason. Does not transfer resources. Choose at most once per invocation.",
                Map.of("optionId", "string", "reason", "string")), (id, call) -> {
            AbstractAgent a = factory.runtime().find(id).orElseThrow();
            String selected = (String) call.arguments().get("optionId");
            if (a.resultData().containsKey("transferChoice")) return ToolResult.failure("A choice has already been published");
            if (!selected.equals("NONE") && environment(factory).transferOptions().stream().noneMatch(o -> o.id().equals(selected))) return ToolResult.failure("Unknown, infeasible or stale option; recalculate first");
            a.publishResult("transferChoice", call.arguments());
            return ToolResult.success("Advisory choice published: " + selected, call.arguments());
        }));
        factory.planner("regional-options", new Planner() {
            public boolean supports(AbstractAgent a, List<Goal> goals, AgentContext c) {
                return goals.size() == 1 && goals.getFirst().description().equalsIgnoreCase("Calculate regional transfers");
            }
            public Plan createPlan(AbstractAgent a, List<Goal> goals, AgentContext c) { return action(a, "calculate_transfers", Map.of()); }
        });
        factory.policies().register("regional-choice", "Deterministically choose an offered food/energy option, or NONE. Only publishes advice; requires choose_transfer. Receives 'Select regional transfer...' requests.",
                Map.of("priority", "food"), options -> {
                    String priority = options.get("priority");
                    if (!Set.of("food", "energy", "none").contains(priority)) throw new IllegalArgumentException("priority must be food, energy or none");
                    return new AgentPolicy() {
                        public boolean supports(AbstractAgent a, List<Goal> goals) { return goals.size() == 1 && goals.getFirst().description().startsWith("Select regional transfer"); }
                        public Plan createPlan(AbstractAgent a, List<Goal> goals) {
                            var choice = environment(factory).transferOptions().stream().filter(o -> o.resource().equals(priority)
                                    && goals.getFirst().description().contains(o.id())).findFirst();
                            return action(a, "choose_transfer", Map.of("optionId", choice.map(ResourceEnvironment.TransferOption::id).orElse("NONE"),
                                    "reason", "Configured deterministic priority: " + priority + (choice.isEmpty() ? "; no matching offered option" : "")));
                        }
                    };
                });
        factory.policies().register("regional-monitor", "On 'Coordinate regional resources', detect shortages, request a planner's feasible options, request a selector's advice, then apply only that valid offered option. Requires request_agent, remember and transfer_resources.",
                Map.of("plannerPeer", "Regional planner", "analystPeer", "Regional selector"), options -> new AgentPolicy() {
                    public boolean supports(AbstractAgent a, List<Goal> goals) {
                        if (goals.size() != 1) return false;
                        String task = goals.getFirst().description();
                        return task.equals(START) || (task.equals(OPTIONS) || task.startsWith(ADVICE)) && a.activeMessage().isPresent();
                    }
                    public Plan createPlan(AbstractAgent a, List<Goal> goals) {
                        String task = goals.getFirst().description();
                        UUID planner = a.context().resolvePeer(options.get("plannerPeer")), analyst = a.context().resolvePeer(options.get("analystPeer"));
                        if (task.equals(START)) {
                            var facts = a.context().environment().snapshot().facts();
                            if (!(facts.get("foodShortage") instanceof Number food) || !(facts.get("energyShortage") instanceof Number energy)) throw new IllegalArgumentException("Configure the regional environment first");
                            if (food.intValue() == 0 && energy.intValue() == 0) return note(a, "No shortages; no peer work or transfer needed");
                            return action(a, "request_agent", Map.of("recipientId", planner.toString(), "task", "Calculate regional transfers", "onResult", OPTIONS));
                        }
                        AgentRequest result = response(a, task.equals(OPTIONS) ? planner : analyst, task);
                        if (result.status() != AgentRequest.Status.SUCCEEDED) return note(a, "No transfer: peer " + result.id() + " " + result.status() + ": " + result.result());
                        if (task.equals(OPTIONS)) {
                            if (!(result.outputs().get("transferOptions") instanceof List<?> candidates) || candidates.isEmpty()) return note(a, "No feasible options: transport or reserves cannot cover shortages");
                            List<String> ids = candidates.stream().filter(Map.class::isInstance).map(Map.class::cast).map(m -> String.valueOf(m.get("optionId"))).toList();
                            return action(a, "request_agent", Map.of("recipientId", analyst.toString(), "task", "Select regional transfer: balance immediate food needs against energy for water pumping and refrigeration. "
                                    + "Transport is shared, so only one of these alternatives will be applied this round. Choose an offered optionId or NONE using choose_transfer, then summarize. "
                                    + "These are the actual planner results: " + candidates, "onResult", ADVICE + String.join(",", ids)));
                        }
                        if (!(result.outputs().get("transferChoice") instanceof Map<?, ?> advice)) return note(a, "No transfer: missing structured choice");
                        String selected = String.valueOf(advice.get("optionId"));
                        if (selected.equals("NONE")) return note(a, "Non-intervention: " + advice.get("reason"));
                        var offered = Arrays.asList(task.substring(ADVICE.length()).split(","));
                        var chosen = environment(factory).transferOptions().stream().filter(o -> offered.contains(o.id()) && o.id().equals(selected)).findFirst();
                        if (chosen.isEmpty()) return note(a, "No transfer: advice was not an offered current feasible option");
                        return action(a, "transfer_resources", chosen.get().arguments());
                    }
                });
    }
    /** Host composition only. All cooperation after initial submission goes through policies, tools and mailboxes. */
    public static List<AbstractAgent> load(AgentRuntime runtime, AgentFactory factory, int food, int energy, int capacity, String selection, LLMConfig online) {
        if (!Set.of("food", "energy", "none", "google", "llm").contains(selection)) throw new IllegalArgumentException("Select a deterministic priority or online LLM");
        ResourceEnvironment env = environment(factory); var before = env.snapshot(); env.configureRegions(food, energy, capacity);
        runtime.trace().publish(new ExecutionEvent(null, ExecutionEventType.ENVIRONMENT_ACTION, null, "scenario-setup", "Regional setup: food shortage=" + food + ", energy shortage=" + energy + ", transport=" + capacity,
                Map.of("before", before, "after", env.snapshot(), "selection", selection)));
        var offline = new LLMConfig("offline", "offline-rules", 0, 1024, Map.of());
        UUID plannerId = UUID.randomUUID(), analystId = UUID.randomUUID();
        var planner = factory.create(plannerId, new AgentProfile("Regional planner", "Feasibility analyst", "Accepts: Calculate regional transfers. Computes typed feasible options without mutation.", List.of()),
                new AgentConfiguration(AgentType.PLANNER, "regional-options", 50, true, true, Set.of("calculate_transfers"), offline), null);
        boolean live = selection.equals("google") || selection.equals("llm");
        if (live && (app.llm.provider.LLMServiceFactory.isOffline(online.provider()) || !app.llm.provider.LLMServiceFactory.providers().contains(online.provider()))) throw new IllegalArgumentException("Choose an online provider for regional analysis; no offline fallback");
        var analyst = factory.create(analystId, new AgentProfile("Regional selector", "Prioritize competing needs", "Accepts: Select regional transfer with offered option IDs.", List.of(),
                "Select one offered feasible option or NONE. Consider the tradeoff between food security and energy for water/refrigeration. Call choose_transfer exactly once with a reason. Never invent a transfer or claim it was executed. After the tool result, summarize briefly without further tools.", ""),
                new AgentConfiguration(live ? AgentType.LLM : AgentType.BASIC, "rules", 50, true, true, Set.of("choose_transfer"), live ? online : offline,
                        live ? "rules" : "regional-choice", live ? Map.of() : Map.of("priority", selection)), null);
        var monitor = factory.create(UUID.randomUUID(), new AgentProfile("Regional monitor", "Observe and coordinate", "Accepts: Coordinate regional resources. Routes calculation and advice, then executes only validated transfers.", List.of()),
                new AgentConfiguration(AgentType.BASIC, "rules", 50, true, true, Set.of("request_agent", "remember", "transfer_resources"), offline,
                        "regional-monitor", Map.of("plannerPeer", plannerId.toString(), "analystPeer", analystId.toString())), null);
        var agents = List.of(monitor, planner, analyst); agents.forEach(runtime::register); runtime.submitGoal(monitor.id(), Goal.of(START)); return agents;
    }
}
