package app.core.agent;

import app.core.capability.CapabilityRegistry;
import app.core.environment.Goal;
import app.core.memory.*;
import app.core.message.*;
import app.core.planning.PlanExecution;
import app.core.runtime.*;
import java.time.Instant;
import java.util.*;

public abstract class AbstractAgent {
    private final UUID id;
    private final AgentProfile profile;
    private final AgentContext context;
    private final List<Goal> pendingGoals = new ArrayList<>();
    private final Map<UUID, AgentMessage> goalMessages = new HashMap<>();
    private AgentMessage activeMessage;
    public Optional<AgentMessage> activeMessage() { return Optional.ofNullable(activeMessage); }
    public boolean supportsConversation() { return false; }
    private volatile AgentStatus status = AgentStatus.IDLE;
    private volatile String lastOutcome = "";
    private volatile boolean lastInvocationFailed;
    /** Work outcome is independent of lifecycle: a provider failure does not break a healthy agent. */
    public boolean lastInvocationFailed() { return lastInvocationFailed; }
    private final Map<String, Object> resultData = new LinkedHashMap<>();
    public synchronized Map<String, Object> resultData() { return Map.copyOf(resultData); }
    public synchronized void publishResult(String key, Object value) {
        if (status != AgentStatus.RUNNING) throw new IllegalStateException("Results are published during agent execution");
        resultData.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
    }
    private volatile PlanExecution lastExecution;
    private volatile java.util.Set<UUID> processedGoals = java.util.Set.of();
    public java.util.Set<UUID> processedGoals() { return processedGoals; }
    private AgentConfiguration configuration;
    private volatile app.llm.LLMStatus llmStatus = app.llm.LLMStatus.configured(null);
    public app.llm.LLMStatus llmStatus() { return llmStatus; }
    public void setLlmStatus(app.llm.LLMStatus status) { llmStatus = Objects.requireNonNull(status); }

    protected AbstractAgent(AgentProfile profile, AgentContext context) { this(UUID.randomUUID(), profile, context); }
    protected AbstractAgent(UUID id, AgentProfile profile, AgentContext context) {
        this.id = Objects.requireNonNull(id); this.profile = Objects.requireNonNull(profile); this.context = Objects.requireNonNull(context);
    }
    public final synchronized void tick(List<Goal> goals) {
        if (!isExecutable()) return;
        status = AgentStatus.RUNNING;
        List<Goal> work = new ArrayList<>(pendingGoals);
        pendingGoals.clear();
        if (goals != null) work.addAll(goals);
        work.sort(Comparator.comparingInt(Goal::priority).reversed());
        // One ready goal per invocation gives each request its own attributable outcome.
        Goal selected = work.stream().filter(this::canProcessGoal).findFirst().orElse(null);
        for (Goal goal : work) if (goal != selected) pendingGoals.add(goal);
        work = selected == null ? List.of() : List.of(selected);
        activeMessage = selected == null ? null : goalMessages.remove(selected.id());
        resultData.clear();
        lastExecution = null;
        lastInvocationFailed = false;
        processedGoals = work.stream().map(Goal::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        try {
            perceive();
            if (!work.isEmpty()) decide(List.copyOf(work));
            lastInvocationFailed = lastExecution != null && lastExecution.status() == PlanExecution.Status.FAILED;
            status = AgentStatus.IDLE;
        } catch (TaskFailure ex) {
            lastInvocationFailed = true;
            status = AgentStatus.IDLE;
            lastOutcome = "Task failed: " + ex.getMessage() + ". Agent is ready for new work; this task was not replayed. Already executed effects remain.";
            memory().putState("lastOutcome", lastOutcome);
            emit(ExecutionEventType.ERROR, lastOutcome);
        } catch (RuntimeException ex) {
            lastInvocationFailed = true;
            status = AgentStatus.ERROR;
            lastOutcome = "Execution failed: " + ex.getMessage();
            memory().putState("lastOutcome", lastOutcome);
            emit(ExecutionEventType.ERROR, lastOutcome);
        } finally { activeMessage = null; }
    }
    public synchronized void receive(AgentMessage message) {
        memory().remember(MemoryEntry.message(message, Instant.now()));
        if (message.type() == MessageType.CONVERSATION) {
            goalMessages.put(message.id(), message);
            submitGoal(new Goal(message.id(), message.content(), 1));
        } else if (message.type() == MessageType.REQUEST) submitGoal(new Goal(
                UUID.fromString(message.metadata().get("goalId")), message.content(), Integer.parseInt(message.metadata().get("priority"))), message);
        else if (message.type() == MessageType.COMMAND || message.type() == MessageType.QUERY) submitGoal(Goal.of(message.content()), message);
        if (message.type() == MessageType.RESPONSE && message.metadata().containsKey("requestId"))
            memory().putState("request:" + message.metadata().get("requestId"),
                    Map.of("status", message.metadata().get("status"), "result", message.content()));
        emit(ExecutionEventType.MESSAGE_RECEIVED, "Received " + message.type() + " from " + message.senderId() + ": " + message.content());
    }
    public synchronized void submitGoal(Goal goal) { pendingGoals.add(Objects.requireNonNull(goal)); }
    public synchronized void submitGoal(Goal goal, AgentMessage source) { goalMessages.put(goal.id(), source); submitGoal(goal); }
    public synchronized void cancelGoal(UUID id) { pendingGoals.removeIf(goal -> goal.id().equals(id)); goalMessages.remove(id); }
    protected boolean canProcessGoal(Goal goal) {
        AgentMessage message = goalMessages.get(goal.id());
        return message == null || message.type() != MessageType.CONVERSATION || context.conversationActive(UUID.fromString(message.metadata().get("conversationId")));
    }
    public synchronized boolean hasPendingWork() { return isExecutable() && pendingGoals.stream().anyMatch(this::canProcessGoal); }
    public boolean isExecutable() { return status == AgentStatus.IDLE || status == AgentStatus.WAITING; }
    public synchronized void changeStatus(AgentStatus status) {
        if (status == AgentStatus.RUNNING || status == AgentStatus.ERROR) throw new IllegalArgumentException("Execution status is runtime controlled");
        this.status = status;
        emit(ExecutionEventType.AGENT_STATE_CHANGED, status.name());
    }
    protected void perceive() {
        memory().remember(MemoryEntry.observation("environment", context.environment().snapshot().summary(), Instant.now()));
    }
    protected abstract void decide(List<Goal> goals);
    protected void complete(String outcome) {
        lastOutcome = outcome == null ? "" : outcome;
        memory().putState("lastOutcome", lastOutcome);
        memory().remember(MemoryEntry.reasoning("agent-outcome", lastOutcome, Instant.now()));
        emit(ExecutionEventType.MEMORY_WRITE, "Stored agent outcome");
        emit(ExecutionEventType.AGENT_RESPONSE, lastOutcome);
    }
    protected void emit(ExecutionEventType type, String message) { context.trace().publish(ExecutionEvent.of(type, id, name(), message)); }
    public UUID id() { return id; }
    public AgentProfile profile() { return profile; }
    public String name() { return profile.name(); }
    public AgentStatus status() { return status; }
    public String lastOutcome() { return lastOutcome; }
    public synchronized Collection<Goal> pendingGoals() { return List.copyOf(pendingGoals); }
    public AgentContext context() { return context; }
    public AgentMemory memory() { return context.memory(); }
    public CapabilityRegistry capabilities() { return context.capabilities(); }
    public void recordExecution(PlanExecution execution) { lastExecution = execution; }
    public PlanExecution lastExecution() { return lastExecution; }
    public void configure(AgentConfiguration configuration) { this.configuration = Objects.requireNonNull(configuration); }
    public AgentConfiguration configuration() { return configuration; }
    public void restoreFrom(AbstractAgent previous) {
        if (!previous.id.equals(id)) throw new IllegalArgumentException("Identity mismatch");
        pendingGoals.addAll(previous.pendingGoals());
        goalMessages.putAll(previous.goalMessages);
        lastOutcome = previous.lastOutcome;
        lastInvocationFailed = previous.lastInvocationFailed;
        resultData.putAll(previous.resultData());
        lastExecution = previous.lastExecution;
        status = previous.status;
        if (configuration != null && previous.configuration != null && configuration.type() == previous.configuration.type()
                && configuration.llm().equals(previous.configuration.llm())) llmStatus = previous.llmStatus;
    }
    @Override public String toString() { return name() + " [" + id + "]"; }
}
