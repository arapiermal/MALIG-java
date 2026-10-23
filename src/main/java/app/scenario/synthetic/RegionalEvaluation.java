package app.scenario.synthetic;

import app.core.agent.*;
import app.core.environment.*;
import app.core.message.DirectMessageBus;
import app.core.runtime.*;
import app.core.capability.ToolResult;
import app.llm.LLMConfig;
import app.llm.provider.LLMServiceFactory;
import java.nio.file.*;
import java.util.*;

/** Small reproducible experiment, not a benchmark framework. Each run gets an independent runtime. */
public final class RegionalEvaluation {
    private RegionalEvaluation() { }
    public record Evidence(String mode, int foodShortage, int energyShortage, int capacity, long durationMillis,
            String outcome, WorldStateSnapshot initial, WorldStateSnapshot end, List<ExecutionEvent> events, String providerStatus) {
        public Evidence { events = List.copyOf(events); }
        public long count(ExecutionEventType type) { return events.stream().filter(e -> e.type() == type).count(); }
        public long deniedTools() { return events.stream().filter(e -> e.type() == ExecutionEventType.ACTION_COMPLETED
                && e.metadata().get("result") instanceof ToolResult r && !r.success()).count(); }
    }
    public static Evidence run(String mode, int food, int energy, int capacity, LLMConfig config) throws Exception {
        var runtime = new AgentRuntime(new SequentialScheduler(), new ResourceEnvironment(), new DirectMessageBus());
        var factory = ResourceScenario.factory(runtime);
        List<ExecutionEvent> events = new ArrayList<>();
        try (var subscription = runtime.trace().subscribe(events::add)) {
            var agents = RegionalCoordination.load(runtime, factory, food, energy, capacity, mode.equals("food-denied") ? "food" : mode, config);
            if (mode.equals("food-denied")) {
                var monitor = agents.getFirst(); var c = monitor.configuration(); var grants = new HashSet<>(c.tools()); grants.remove("transfer_resources");
                runtime.replace(factory.create(monitor.id(), monitor.profile(), new AgentConfiguration(c.type(), c.planner(), c.memoryLimit(),
                        c.contextMemory(), c.episodicMemory(), grants, c.llm(), c.policy(), c.policyOptions()), monitor.memory()));
            }
            var initial = runtime.environment().snapshot(); long start = System.nanoTime();
            for (int tick = 0; tick < 20 && runtime.hasReadyWork(); tick++) runtime.step();
            long duration = (System.nanoTime() - start) / 1_000_000;
            boolean failed = runtime.hasReadyWork() || runtime.agents().stream().anyMatch(AbstractAgent::lastInvocationFailed)
                    || runtime.requests().stream().anyMatch(r -> r.status() != AgentRequest.Status.SUCCEEDED);
            return new Evidence(mode, food, energy, capacity, duration,
                    (failed ? "FAILED/INCOMPLETE: " : "COMPLETED: ") + runtime.find(agents.getFirst().id()).orElseThrow().lastOutcome(), initial, runtime.environment().snapshot(), events,
                    agents.get(2).llmStatus().toString());
        }
    }
    /** CSV files preserve raw event/correlation details plus normalized region and count tables. */
    public static void export(Path directory, String prefix, List<Evidence> runs) throws java.io.IOException {
        Files.createDirectories(directory);
        StringBuilder summary = new StringBuilder(csv("run", "mode", "foodShortage", "energyShortage", "transport", "durationMillis", "outcome", "requests", "messagesSent", "messagesReceived", "toolCalls", "failedTools", "llmCalls", "llmFailures", "providerStatus"));
        StringBuilder states = new StringBuilder(csv("run", "phase", "region", "food", "foodNeed", "energy", "energyNeed", "transportRemaining"));
        StringBuilder trace = new StringBuilder(csv("run", "sequence", "time", "type", "agentId", "source", "message", "details"));
        for (int index = 0; index < runs.size(); index++) {
            var run = runs.get(index); String id = prefix + "-" + index;
            summary.append(csv(id, run.mode(), run.foodShortage(), run.energyShortage(), run.capacity(), run.durationMillis(), run.outcome(),
                    run.count(ExecutionEventType.REQUEST_SENT), run.count(ExecutionEventType.MESSAGE_SENT), run.count(ExecutionEventType.MESSAGE_RECEIVED),
                    run.count(ExecutionEventType.CAPABILITY_CALLED), run.deniedTools(), run.count(ExecutionEventType.LLM_REQUEST), run.count(ExecutionEventType.LLM_FAILURE), run.providerStatus()));
            for (var phase : List.of(Map.entry("initial", run.initial()), Map.entry("final", run.end()))) for (var entity : phase.getValue().entities()) {
                var a = entity.attributes(); states.append(csv(id, phase.getKey(), entity.name(), a.get("food"), a.get("foodNeed"), a.get("energy"), a.get("energyNeed"), phase.getValue().facts().get("transportRemaining")));
            }
            int sequence = 0;
            for (var event : run.events()) trace.append(csv(id, sequence++, event.occurredAt(), event.type(), event.agentId(), event.source(), event.message(), event.metadata()));
        }
        Files.writeString(directory.resolve(prefix + "-summary.csv"), summary);
        Files.writeString(directory.resolve(prefix + "-states.csv"), states);
        Files.writeString(directory.resolve(prefix + "-events.csv"), trace);
    }
    private static String csv(Object... values) {
        return Arrays.stream(values).map(v -> "\"" + String.valueOf(v).replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(",")) + "\n";
    }
    public static void main(String[] args) throws Exception {
        boolean live = Arrays.asList(args).contains("--google");
        var config = live ? new LLMConfig("google", System.getProperty("hmas.liveModel", LLMServiceFactory.defaultModel("google")), .2, 4096,
                Map.of(LLMConfig.TEMPERATURE_MODE, "provider-default")) : LLMServiceFactory.defaults();
        List<Evidence> evidence = new ArrayList<>();
        for (String mode : live ? List.of("google") : List.of("food", "energy", "none"))
            for (int[] setup : List.of(new int[]{30, 25, 20}, new int[]{0, 0, 20}, new int[]{30, 25, 0}))
                evidence.add(run(mode, setup[0], setup[1], setup[2], config));
        if (!live) evidence.add(run("food-denied", 30, 25, 20, config));
        export(Path.of("target/evaluation"), live ? "google" : "deterministic", evidence);
    }
}
