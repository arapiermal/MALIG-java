package com.erimali.multiAgentFX.bridge;

import app.core.agent.*;
import app.core.environment.*;
import app.core.message.*;
import app.core.runtime.*;
import app.llm.provider.LLMServiceFactory;
import com.erimali.multiAgentFX.model.AgentConfig;
import javafx.application.Platform;
import javafx.collections.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** JavaFX projection over the canonical runtime; serialized work keeps network calls off the FX thread. */
public final class RuntimeFacade implements AutoCloseable {
    private final ObservableList<AgentConfig> drafts = FXCollections.observableArrayList();
    private final AgentRuntime runtime;
    private final AgentFactory factory;
    private final ScenarioBootstrap scenario;
    private final Set<UUID> demoAgents = new LinkedHashSet<>();
    private final Set<UUID> conversationPair = new LinkedHashSet<>();
    public String scenarioName() { return scenario.name(); }
    private final AgentUIMapper mapper = new AgentUIMapper();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hmas-runtime"); t.setDaemon(true); return t;
    });
    public RuntimeFacade(ScenarioBootstrap scenario) {
        this.scenario = Objects.requireNonNull(scenario);
        runtime = new AgentRuntime(new SequentialScheduler(), scenario.createEnvironment(), new DirectMessageBus());
        factory = new AgentFactory(runtime, LLMServiceFactory::forConfig);
        scenario.configure(factory);
        worker.scheduleWithFixedDelay(() -> {
            try {
                if (runtime.hasActiveConversations() || runtime.hasActiveCollaborations()) runtime.step();
                else { if (runtime.needsCleanup()) runtime.cleanupSessions(); runtime.runCycle(); }
            } catch (RuntimeException ex) { report(ex); runtime.stop(); }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }
    public CompletableFuture<UUID> startConversation(List<UUID> participants, String topic, int maxTurns) {
        return CompletableFuture.supplyAsync(() -> runtime.startConversation(participants, topic, maxTurns), worker);
    }
    public void cancelConversation(UUID id) { runtime.cancelConversation(id); }
    public CompletableFuture<UUID> startCollaboration(UUID initiator, UUID reviewer, String task) {
        return CompletableFuture.supplyAsync(() -> runtime.startCollaboration(initiator, reviewer, task), worker);
    }
    public void cancelCollaboration(UUID id) { runtime.cancelCollaboration(id); runAsync(runtime::cleanupSessions); }
    /** Advance asynchronous request/result continuations, with a finite host budget. Human/paused work stays pending. */
    public void drainWork() {
        for (int i = 0; i < 20 && runtime.hasReadyWork(); i++) runtime.step();
        if (runtime.hasReadyWork()) throw new IllegalStateException("20 work cycles exhausted. Inspect pending work; use Step to continue.");
    }
    public CompletableFuture<Void> createConversationPair() {
        var config = LLMServiceFactory.defaults();
        return runAsync(() -> {
            if (conversationPair.size() == 2 && conversationPair.stream().allMatch(id -> runtime.find(id).isPresent())) return;
            if (conversationPair.stream().anyMatch(id -> runtime.find(id).isPresent())) throw new IllegalStateException("One preset conversation agent remains. Create the other in Agents, or remove the remaining preset before recreating the pair.");
            conversationPair.clear();
            List<AbstractAgent> pair = new ArrayList<>();
            String[] names = {"Analyst", "Reviewer"};
            String[] roles = {"Propose a practical approach", "Evaluate the supplied task or proposal"};
            String[] instructions = {
                "Respond to the actual task and context supplied. For discussion, propose an approach and explain the tradeoffs. Reflect on actual feedback when supplied. Do not assume a prior review or claim calculations/effects without the required capabilities and results. Keep replies under 150 words.",
                "Respond to the actual task and context supplied. When a proposal is supplied, identify weaknesses and suggest concrete improvements. Never assume a proposal or earlier conversation exists. If required inputs or capabilities are missing, state that limitation; do not claim completion. Keep replies under 150 words."
            };
            for (int i = 0; i < 2; i++) {
                var agent = factory.create(UUID.randomUUID(), new AgentProfile(names[i], roles[i], "", List.of(),
                        instructions[i], ""),
                        new AgentConfiguration(AgentType.LLM, "auto", 50, true, true, Set.of(), config), null);
                runtime.register(agent); pair.add(agent); conversationPair.add(agent.id());
            }
            Platform.runLater(() -> pair.forEach(a -> drafts.add(mapper.fromAgent(a))));
        });
    }
    public ObservableList<AgentConfig> agents() { return FXCollections.unmodifiableObservableList(drafts); }
    /** Explicit user action; defaults never overwrite existing per-agent settings automatically. */
    public CompletableFuture<Void> applyConversationDefaults(List<UUID> participants) {
        var defaults = LLMServiceFactory.defaults();
        return runAsync(() -> {
            if (participants.size() != 2 || new HashSet<>(participants).size() != 2) throw new IllegalArgumentException("Select two distinct LLM agents");
            if (runtime.hasActiveConversations() || runtime.hasActiveCollaborations()) throw new IllegalStateException("Finish or cancel active sessions before changing participant settings");
            List<AbstractAgent> replacements = new ArrayList<>();
            for (UUID id : participants) {
                var previous = runtime.find(id).orElseThrow();
                if (!previous.supportsConversation() || previous.hasPendingWork()) throw new IllegalArgumentException("Select idle LLM agents without pending work");
                var c = mapper.fromAgent(previous);
                c.setProvider(defaults.provider()); c.setModel(defaults.model()); c.setTemperature(defaults.temperature());
                c.setMaxTokens(defaults.maxTokens()); c.setLlmOptions(defaults.options());
                replacements.add(factory.create(id, mapper.toProfile(c), mapper.toConfiguration(c), previous.memory()));
            }
            replacements.forEach(runtime::replace);
            Platform.runLater(() -> replacements.forEach(a -> {
                AgentConfig old = drafts.stream().filter(c -> c.getId().equals(a.id())).findFirst().orElse(null);
                if (old != null) drafts.set(drafts.indexOf(old), mapper.fromAgent(a));
            }));
        });
    }
    public AgentConfig newDraft() { AgentConfig c = new AgentConfig(); c.setTools(Set.of()); return c; }
    public AgentFactory factory() { return factory; }
    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, worker).whenComplete((ignored, error) -> { if (error != null) report(error); });
    }
    private void report(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        runtime.trace().publish(ExecutionEvent.of(ExecutionEventType.ERROR, null, "runtime", cause.getMessage()));
    }
    public CompletableFuture<Void> saveAgent(AgentConfig draft) {
        AgentConfig c = draft.copy();
        return runAsync(() -> {
            AbstractAgent previous = runtime.find(c.getId()).orElse(null);
            AbstractAgent agent = factory.create(c.getId(), mapper.toProfile(c), mapper.toConfiguration(c),
                    previous == null ? null : previous.memory());
            if (previous == null) runtime.register(agent); else runtime.replace(agent);
            if (!c.isEnabled()) runtime.setAgentStatus(c.getId(), AgentStatus.STOPPED);
            else if (previous == null || previous.status() == AgentStatus.STOPPED) runtime.setAgentStatus(c.getId(), AgentStatus.IDLE);
            Platform.runLater(() -> {
                AgentConfig old = drafts.stream().filter(a -> a.getId().equals(c.getId())).findFirst().orElse(null);
                if (old == null) drafts.add(c); else drafts.set(drafts.indexOf(old), c);
            });
        });
    }
    public void deleteAgent(AgentConfig c) {
        runAsync(() -> { runtime.remove(c.getId()); Platform.runLater(() -> drafts.removeIf(d -> d.getId().equals(c.getId()))); });
    }
    public Optional<AgentConfig> findAgent(UUID id) { return drafts.stream().filter(a -> a.getId().equals(id)).findFirst(); }
    public Optional<AbstractAgent> findRuntimeAgent(UUID id) { return runtime.find(id); }
    public CompletableFuture<Void> submitTask(UUID id, String task) {
        return submitTrackedTask(id, task).thenApply(receipt -> null);
    }
    public record WorkSubmission(UUID id, String advancementNote) { }
    /** Retains an accepted ID even when bounded advancement stops or throws after real work occurred. */
    private String advanceAcceptedWork() {
        try { drainWork(); return "Bounded advancement finished; inspect the recorded outcome. Paused/human work can remain pending."; }
        catch (RuntimeException ex) {
            report(ex); return "Work was accepted and remains recorded. Advancement stopped: " + ex.getMessage() + ". Use Step to continue; do not resubmit to retry advancement.";
        }
    }
    public CompletableFuture<WorkSubmission> submitTrackedTask(UUID id, String task) {
        return CompletableFuture.supplyAsync(() -> {
            if (task == null || task.isBlank()) throw new IllegalArgumentException("Enter a task");
            UUID goalId = runtime.submitUserTask(id, Goal.of(task));
            return new WorkSubmission(goalId, advanceAcceptedWork());
        }, worker);
    }
    public CompletableFuture<UUID> requestWork(UUID requester, UUID recipient, String task, String onResult) {
        return requestTrackedWork(requester, recipient, task, onResult).thenApply(WorkSubmission::id);
    }
    public CompletableFuture<WorkSubmission> requestTrackedWork(UUID requester, UUID recipient, String task, String onResult) {
        return CompletableFuture.supplyAsync(() -> {
            if (task == null || task.isBlank()) throw new IllegalArgumentException("Enter recipient work");
            UUID id = runtime.request(requester, recipient, Goal.of(task), onResult);
            return new WorkSubmission(id, advanceAcceptedWork());
        }, worker);
    }
    public CompletableFuture<Boolean> cancelPendingTask(UUID id) { return CompletableFuture.supplyAsync(() -> runtime.cancelUserTask(id), worker); }
    public CompletableFuture<AgentRuntime.EnvironmentInspection> inspectEnvironment() {
        return CompletableFuture.supplyAsync(runtime::inspectEnvironment, worker);
    }
    public CompletableFuture<Void> changeEnvironmentFact(EnvironmentManagement.Change change) {
        return runAsync(() -> runtime.changeEnvironmentFact(change));
    }
    public void sendMessage(UUID sender, UUID recipient, String content) {
        runAsync(() -> { runtime.send(AgentMessage.direct(sender, recipient, content)); drainWork(); });
    }
    public void step() { runAsync(runtime::step); }
    public void setAgentStatus(UUID id, AgentStatus status) { runAsync(() -> runtime.setAgentStatus(id, status)); }
    public AutoCloseable onEvent(Consumer<ExecutionEvent> listener) { return runtime.trace().subscribe(listener); }
    public List<ExecutionEvent> recentEvents(int limit) { return runtime.trace().recent(limit); }
    public AgentRuntime runtime() { return runtime; }
    public String runtimeSummary() {
        return "Automatic cycles " + (runtime.isRunning() ? "RUNNING" : "STOPPED")
                + " | active conversations=" + runtime.conversations().stream().filter(ConversationSession.Snapshot::active).count()
                + " | active collaborations=" + runtime.collaborations().stream().filter(CollaborationTask.Snapshot::active).count()
                + " | agents=" + runtime.agents().size()
                + " | tick=" + runtime.tickCount() + " | messages=" + runtime.messagesProcessed();
    }
    public void loadDemoScenario() {
        var provider = LLMServiceFactory.defaults();
        if (supportsResourceDemo()) { runRegionalDemo(30, 25, 20, LLMServiceFactory.isOffline(provider.provider()) ? "food" : "llm", provider); return; }
        runAsync(() -> {
            if (!runtime.agents().isEmpty()) throw new IllegalStateException("Use the Hybrid Demo tab to configure another run alongside existing agents");
            var agents = scenario.loadDemo(runtime, factory, provider);
            Platform.runLater(() -> agents.forEach(a -> drafts.add(mapper.fromAgent(a))));
            drainWork();
        });
    }
    public boolean supportsResourceDemo() { return scenario instanceof app.scenario.synthetic.ResourceScenario; }
    private void prepareDemo() {
        if (runtime.needsCleanup()) runtime.cleanupSessions();
        if (runtime.isRunning() || runtime.hasActiveConversations() || runtime.hasActiveCollaborations() || runtime.hasReadyWork()
                || runtime.agents().stream().anyMatch(a -> !a.pendingGoals().isEmpty())
                || runtime.requests().stream().anyMatch(r -> r.status() == AgentRequest.Status.PENDING))
            throw new IllegalStateException("Stop cycles and finish pending work before resetting the scenario");
        var removed = Set.copyOf(demoAgents); removed.forEach(runtime::remove); demoAgents.clear();
        Platform.runLater(() -> drafts.removeIf(a -> removed.contains(a.getId())));
    }
    private void registerDemoDrafts(List<AbstractAgent> agents) {
        agents.forEach(a -> demoAgents.add(a.id()));
        Platform.runLater(() -> agents.forEach(a -> drafts.add(mapper.fromAgent(a))));
    }
    public CompletableFuture<Void> runRegionalDemo(int food, int energy, int capacity, String selection, app.llm.LLMConfig provider) {
        return runAsync(() -> {
            if (!supportsResourceDemo()) throw new IllegalStateException("Requires the resource scenario");
            if (food < 0 || food > 80 || energy < 0 || energy > 70 || capacity < 0 || capacity > 100
                    || !Set.of("food", "energy", "none", "google", "llm").contains(selection)) throw new IllegalArgumentException("Invalid regional settings");
            if ((selection.equals("google") || selection.equals("llm")) && (LLMServiceFactory.isOffline(provider.provider()) || !LLMServiceFactory.providers().contains(provider.provider()))) throw new IllegalArgumentException("Select an online provider for live analysis");
            prepareDemo();
            registerDemoDrafts(app.scenario.synthetic.RegionalCoordination.load(runtime, factory, food, energy, capacity, selection, provider));
            drainWork();
        });
    }
    public CompletableFuture<Void> runResourceDemo(int initial, int target, int threshold, app.llm.LLMConfig provider) {
        return runAsync(() -> {
            if (!(scenario instanceof app.scenario.synthetic.ResourceScenario resource)) throw new IllegalStateException("This scenario does not offer a resource demo");
            if (runtime.isRunning() || runtime.hasActiveConversations() || runtime.hasActiveCollaborations() || runtime.hasReadyWork()
                    || runtime.agents().stream().anyMatch(a -> !a.pendingGoals().isEmpty())
                    || runtime.requests().stream().anyMatch(r -> r.status() == AgentRequest.Status.PENDING))
                throw new IllegalStateException("Stop automatic cycles and finish pending work before resetting the resource scenario");
            if (initial < 0 || initial > 100 || target < 0 || target > 100 || threshold < 0 || threshold > 100) throw new IllegalArgumentException("Levels must be 0..100");
            prepareDemo();
            var added = resource.loadDemo(runtime, factory, provider, initial, target, threshold);
            registerDemoDrafts(added);
            drainWork();
        });
    }
    @Override public void close() { runtime.stop(); worker.shutdownNow(); }
}
