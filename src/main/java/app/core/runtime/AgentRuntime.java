package app.core.runtime;

import app.core.agent.*;
import app.core.capability.*;
import app.core.environment.*;
import app.core.memory.*;
import app.core.message.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** One authority for lifecycle, message routing, validated execution, hierarchy, and history. */
public final class AgentRuntime {
    private final Map<UUID, AbstractAgent> agents = new LinkedHashMap<>();
    private final Map<UUID, UUID> parents = new LinkedHashMap<>();
    private final Map<UUID, Delegation> delegations = new LinkedHashMap<>();
    private final Map<UUID, AgentRequest> requests = new LinkedHashMap<>();
    private final Map<UUID, UserTask> userTasks = new LinkedHashMap<>();
    private final Map<UUID, ConversationSession> conversations = new LinkedHashMap<>();
    private final Map<UUID, CollaborationTask> collaborations = new LinkedHashMap<>();
    private final Map<String, java.util.function.Function<Map<String, Object>, Optional<String>>> resultContracts = new LinkedHashMap<>();
    /** Scenario/application-declared output checks; absent contracts never imply verified achievement. */
    public synchronized void resultContract(String task, java.util.function.Function<Map<String, Object>, Optional<String>> validator) {
        resultContracts.put(task.strip().toLowerCase(Locale.ROOT), Objects.requireNonNull(validator));
    }
    private final Set<UUID> receivedRequests = new HashSet<>();
    private final Set<UUID> receivedResults = new HashSet<>();
    private final Map<UUID, AgentMessage> requestResponses = new HashMap<>();
    private final Object executionLock = new Object();
    private final Scheduler scheduler;
    private final EnvironmentContext environment;
    private final MessageBus messageBus;
    private final ExecutionTrace trace;
    private final AtomicLong tick = new AtomicLong();
    private final AtomicLong messagesProcessed = new AtomicLong();
    private volatile boolean running;
    private volatile boolean cleanupRequested;

    public AgentRuntime(Scheduler scheduler, EnvironmentContext environment, MessageBus messageBus) {
        this(scheduler, environment, messageBus, new ExecutionTrace());
    }
    public AgentRuntime(Scheduler scheduler, EnvironmentContext environment, MessageBus messageBus, ExecutionTrace trace) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.environment = Objects.requireNonNull(environment);
        this.messageBus = Objects.requireNonNull(messageBus);
        this.trace = Objects.requireNonNull(trace);
        trace.subscribe(this::observeUserTask);
        trace.subscribe(event -> {
            if (event.agentId() != null && Set.of(ExecutionEventType.ACTION_COMPLETED, ExecutionEventType.TASK_DELEGATED,
                    ExecutionEventType.TASK_COMPLETED, ExecutionEventType.REQUEST_SENT, ExecutionEventType.REQUEST_COMPLETED,
                    ExecutionEventType.REQUEST_RESULT_RECEIVED, ExecutionEventType.LLM_FAILURE, ExecutionEventType.PLAN_COMPLETED, ExecutionEventType.DECISION_MADE, ExecutionEventType.ERROR).contains(event.type())) {
                find(event.agentId()).ifPresent(agent -> agent.memory().remember(new MemoryEntry(null, "episode",
                        event.source(), event.message(), event.occurredAt(), Map.of("eventType", event.type().name(),
                        "details", event.metadata().toString()))));
            }
        });
    }
    public synchronized void register(AbstractAgent agent) {
        if (agents.containsKey(agent.id())) throw new IllegalArgumentException("Agent already registered");
        List<Goal> initialGoals = agent.profile().goals().stream().map(Goal::of).toList();
        agent.context().bind(this, agent.id());
        applyConfiguration(agent);
        agents.put(agent.id(), agent);
        trace.publish(ExecutionEvent.of(ExecutionEventType.AGENT_CREATED, agent.id(), "runtime", "Registered " + agent.name()));
        initialGoals.forEach(goal -> submitGoal(agent.id(), goal));
    }
    public void replace(AbstractAgent agent) {
        synchronized (executionLock) {
            synchronized (this) {
                AbstractAgent previous = requireAgent(agent.id());
                if (previous instanceof HumanAgent human && human.hasProposals() && !(agent instanceof HumanAgent))
                    throw new IllegalArgumentException("Execute pending human proposals before changing agent type");
                if (previous.memory() != agent.memory()) throw new IllegalArgumentException("Reconfiguration must preserve memory");
                if (!agent.supportsConversation() && conversations.values().stream().anyMatch(c ->
                        c.status == ConversationSession.Status.RUNNING && c.participants.contains(agent.id())))
                    throw new IllegalArgumentException("Cancel the active conversation before changing controller type");
                agent.context().bind(this, agent.id());
                agent.restoreFrom(previous);
                applyConfiguration(agent);
                agents.put(agent.id(), agent);
            }
            trace.publish(ExecutionEvent.of(ExecutionEventType.AGENT_UPDATED, agent.id(), "runtime", "Reconfigured " + agent.name()));
        }
    }
    public Optional<AbstractAgent> remove(UUID id) {
        synchronized (executionLock) {
            synchronized (this) {
                AbstractAgent removed = agents.remove(id);
                if (removed == null) return Optional.empty();
                for (UserTask task : List.copyOf(userTasks.values())) if (task.agentId().equals(id) && !task.terminal())
                    removed.cancelGoal(task.id());
                parents.remove(id);
                parents.entrySet().removeIf(e -> e.getValue().equals(id));
                messageBus.remove(id);
                cancelRequests(id);
                conversations.values().stream().filter(c -> c.participants.contains(id)).forEach(c ->
                        endConversation(c, ConversationSession.Status.CANCELLED, "Participant removed"));
                delegations.replaceAll((key, task) -> task.status() == Delegation.Status.PENDING
                        && (task.childId().equals(id) || task.parentId().equals(id))
                        ? task.finish(Delegation.Status.CANCELLED, "Agent removed") : task);
                trace.publish(ExecutionEvent.of(ExecutionEventType.AGENT_REMOVED, id, "runtime", "Removed " + removed.name()));
                return Optional.of(removed);
            }
        }
    }
    public synchronized Optional<AbstractAgent> find(UUID id) { return Optional.ofNullable(agents.get(id)); }
    public synchronized Collection<AbstractAgent> agents() { return List.copyOf(agents.values()); }
    public synchronized UUID resolveAgent(String nameOrId) {
        try { UUID id = UUID.fromString(nameOrId); requireAgent(id); return id; }
        catch (IllegalArgumentException ignored) { }
        var matches = agents.values().stream().filter(a -> a.name().equals(nameOrId)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Peer name must identify exactly one registered agent; use discovery or an exact UUID: " + nameOrId);
        return matches.getFirst().id();
    }
    public synchronized List<Map<String, Object>> directory() {
        return agents.values().stream().map(a -> {
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("id", a.id().toString()); entry.put("name", a.name());
            entry.put("role", a.profile().role()); entry.put("description", a.profile().description()); entry.put("status", a.status().name());
            if (a.configuration() != null) { entry.put("controller", a.configuration().type().name()); entry.put("policy", a.configuration().policy()); entry.put("planner", a.configuration().planner()); entry.put("policyOptions", a.configuration().policyOptions()); }
            entry.put("tools", a.capabilities().all().stream().map(c -> Map.of("name", c.name(), "description", c.schema().description(), "arguments", c.schema().parametersSchema())).toList());
            return Map.copyOf(entry);
        }).toList();
    }
    private AbstractAgent requireAgent(UUID id) { return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + id)); }
    public void step(List<Goal> goals) {
        if (goals != null) agents().forEach(a -> goals.forEach(a::submitGoal));
        step();
    }
    /** Manual step is allowed while stopped; runCycle is gated by start/stop. */
    public void step() {
        synchronized (executionLock) {
            cleanupRequested = false;
            maintainConversations();
            maintainCollaborations();
            for (AbstractAgent agent : agents()) {
                for (AgentMessage message : messageBus.receive(agent.id())) {
                    boolean conversationMessage = message.metadata().containsKey("conversationId") || message.type() == MessageType.CONVERSATION;
                    if (conversationMessage && !acceptConversationMessage(message)) continue;
                    if (message.type() == MessageType.REQUEST) {
                        AgentRequest request;
                        synchronized (this) { request = requests.get(message.id()); }
                        if (request == null || request.status() != AgentRequest.Status.PENDING
                                || !request.recipientId().equals(agent.id()) || !request.requesterId().equals(message.senderId())
                                || !request.goal().id().toString().equals(message.metadata().get("goalId"))
                                || !receivedRequests.add(message.id())) continue;
                    }
                    if (message.type() == MessageType.RESPONSE && message.metadata().containsKey("requestId")) {
                        UUID requestId;
                        try { requestId = UUID.fromString(message.metadata().get("requestId")); }
                        catch (IllegalArgumentException ex) { continue; }
                        synchronized (this) {
                            if (!message.equals(requestResponses.get(requestId)) || !receivedResults.add(requestId)) continue;
                        }
                    }
                    agent.receive(message); messagesProcessed.incrementAndGet();
                    if (conversationMessage) conversationMessageDelivered(message);
                    if (message.type() == MessageType.RESPONSE && message.metadata().containsKey("requestId")) {
                        UUID requestId = UUID.fromString(message.metadata().get("requestId"));
                        AgentRequest request;
                        synchronized (this) { request = requests.get(requestId); }
                        if (request != null) {
                            trace.publish(new ExecutionEvent(null, ExecutionEventType.REQUEST_RESULT_RECEIVED, agent.id(), "requests",
                                    message.content(), Map.of("requestId", requestId, "status", request.status(), "completionEvidence", request.evidence())));
                            agent.memory().putState("request:" + requestId, Map.of("status", request.status(), "completionEvidence", request.evidence(), "result", request.result(), "outputs", request.outputs()));
                            if (!request.onResult().isBlank()) {
                              synchronized (this) {
                                CollaborationTask collaboration = collaborationForRequest(requestId);
                                if (collaboration != null && !collaboration.active()) continue;
                                if (collaboration != null && request.status() != AgentRequest.Status.SUCCEEDED) {
                                    endCollaboration(collaboration, CollaborationTask.Status.FAILED, "Peer work " + request.status() + ": " + request.result());
                                    continue;
                                }
                                Goal continuation = Goal.of(request.onResult());
                                agent.submitGoal(continuation, message);
                                if (collaboration != null) {
                                    collaboration.goalId = continuation.id(); collaboration.feedback = request.result();
                                    collaboration.status = CollaborationTask.Status.REVISING; collaborationEvent(collaboration);
                                }
                                trace.publish(new ExecutionEvent(null, ExecutionEventType.GOAL_SUBMITTED, agent.id(), "requests",
                                        continuation.description(), Map.of("goalId", continuation.id(), "requestId", requestId)));
                              }
                            }
                        }
                    }
                }
            }
            scheduler.run(agents().stream().filter(AbstractAgent::hasPendingWork).toList(), List.of());
            finishDelegations();
            finishRequests();
            finishConversations();
            finishCollaborations();
            var before = environment.snapshot();
            environment.advanceTick();
            tick.incrementAndGet();
            trace.publish(new ExecutionEvent(null, ExecutionEventType.ENVIRONMENT_TICK, null, "environment",
                    "Advanced to tick " + tick.get(), Map.of("before", before, "after", environment.snapshot(), "tick", tick.get())));
            synchronized (this) {
                maintainConversations(); maintainCollaborations(); cleanupRequested = false;
            }
        }
    }
    public void runCycle() { if (running) step(); }
    public void send(AgentMessage message) {
        requireAgent(message.senderId());
        if (message.recipientId() != null) requireAgent(message.recipientId());
        route(message);
    }
    private void route(AgentMessage message) {
        messageBus.send(message);
        trace.publish(new ExecutionEvent(null, ExecutionEventType.MESSAGE_SENT, message.senderId(), "message-bus",
                message.content(), Map.of("messageId", message.id(), "recipient", String.valueOf(message.recipientId()),
                "type", message.type().name(), "metadata", message.metadata())));
    }
    public void submitGoal(UUID id, Goal goal) {
        requireAgent(id).submitGoal(goal);
        trace.publish(new ExecutionEvent(null, ExecutionEventType.GOAL_SUBMITTED, id, "runtime",
                goal.description(), Map.of("goalId", goal.id())));
    }
    /** Explicit host submission using the canonical goal queue; unrelated agent/session work is not user history. */
    public UUID submitUserTask(UUID agentId, Goal goal) {
        synchronized (executionLock) {
            synchronized (this) {
                AbstractAgent agent = requireAgent(agentId);
                if (userTasks.containsKey(goal.id())) throw new IllegalArgumentException("User goal ID already submitted");
                userTasks.put(goal.id(), new UserTask(agentId, agent.name(), goal, UserTask.Status.PENDING, java.time.Instant.now(),
                        null, null, "", Map.of(), app.llm.LLMStatus.configured(null)));
                submitGoal(agentId, goal); return goal.id();
            }
        }
    }
    public synchronized List<UserTask> userTasks() { return List.copyOf(userTasks.values()); }
    public synchronized UserTask userTask(UUID id) {
        return Optional.ofNullable(userTasks.get(id)).orElseThrow(() -> new IllegalArgumentException("Unknown user task"));
    }
    /** Pending work only. In-flight calls/effects are not interrupted or rolled back. */
    public boolean cancelUserTask(UUID id) {
        synchronized (executionLock) {
            synchronized (this) {
                var task = userTask(id);
                if (task.status() != UserTask.Status.PENDING) return false;
                requireAgent(task.agentId()).cancelGoal(id); return userTask(id).status() == UserTask.Status.CANCELLED;
            }
        }
    }
    @SuppressWarnings("unchecked")
    private synchronized void observeUserTask(ExecutionEvent event) {
        if (!(event.metadata().get("goalId") instanceof UUID goalId)) return;
        UserTask task = userTasks.get(goalId);
        if (task == null || task.terminal() || !task.agentId().equals(event.agentId())) return;
        switch (event.type()) {
            case GOAL_STARTED -> userTasks.put(goalId, task.started(event.occurredAt()));
            case GOAL_COMPLETED -> userTasks.put(goalId, task.finished(Boolean.TRUE.equals(event.metadata().get("failed")) ? UserTask.Status.FAILED : UserTask.Status.SUCCEEDED,
                    event.occurredAt(), event.message(), (Map<String, Object>) event.metadata().get("outputs"), (app.llm.LLMStatus) event.metadata().get("providerStatus")));
            case GOAL_CANCELLED -> userTasks.put(goalId, task.finished(UserTask.Status.CANCELLED, event.occurredAt(), event.message(), Map.of(), task.provider()));
            default -> { }
        }
    }
    public synchronized boolean requestResponseDelivered(UUID requestId) { return receivedResults.contains(requestId); }
    public ToolResult invoke(UUID id, ToolCall call, String plan, String step) {
        synchronized (executionLock) {
            AbstractAgent agent = requireAgent(id);
            if (agent.status() != AgentStatus.RUNNING) return ToolResult.failure("Tools execute only during an agent invocation");
            if (!invocationActive(id)) return ToolResult.failure("Session cancelled; no new tool execution admitted");
            var before = environment.snapshot();
            trace.publish(ExecutionEvent.of(ExecutionEventType.CAPABILITY_CALLED, id, "tool", call.name()));
            ToolResult result = agent.capabilities().execute(id, call);
            trace.publish(new ExecutionEvent(null, ExecutionEventType.ACTION_COMPLETED, id, "tool",
                    call.name() + ": " + result.message(), Map.of("plan", plan, "step", step, "call", call,
                    "result", result, "before", before, "after", environment.snapshot(), "tick", tick.get())));
            return result;
        }
    }
    /** Admission check between provider/tool rounds. Already admitted effects are never rolled back. */
    public synchronized boolean invocationActive(UUID id) {
        var goals = requireAgent(id).processedGoals();
        for (ConversationSession session : conversations.values())
            if (session.pending != null && goals.contains(session.pending.id()) && session.status != ConversationSession.Status.RUNNING) return false;
        for (CollaborationTask work : collaborations.values()) {
            var request = requests.get(work.requestId);
            if ((work.initiator.equals(id) && goals.contains(work.goalId)
                    || request != null && work.reviewer.equals(id) && goals.contains(request.goal().id())) && !work.active()) return false;
        }
        return true;
    }
    /** Wrap a scenario action as a granted, validated capability; no plan fallback bypass exists. */
    public Capability environmentCapability(ToolSchema schema) {
        if (environment.actions().stream().noneMatch(s -> s.equals(schema))) throw new IllegalArgumentException("Unknown action schema");
        return new ToolCapability(schema, (id, call) -> {
            if (requireAgent(id).status() != AgentStatus.RUNNING) return ToolResult.failure("Agent is not executing");
            AgentAction action = new AgentAction(id, call.name(), call.arguments());
            ActionResult validation = environment.validate(action);
            ActionResult result = validation.success() ? environment.execute(action) : validation;
            return new ToolResult(result.success(), result.message(), result.outputs());
        });
    }
    public void setAgentStatus(UUID id, AgentStatus status) {
        synchronized (executionLock) { requireAgent(id).changeStatus(status); }
    }
    public synchronized void setParent(UUID child, UUID parent) {
        requireAgent(child);
        if (parent != null) {
            requireAgent(parent);
            for (UUID cursor = parent; cursor != null; cursor = parents.get(cursor))
                if (cursor.equals(child)) throw new IllegalArgumentException("Hierarchy must be acyclic");
            parents.put(child, parent);
        } else parents.remove(child);
        trace.publish(ExecutionEvent.of(ExecutionEventType.HIERARCHY_CHANGED, child, "organization", "Parent: " + parent));
    }
    public synchronized Map<String, Object> organizationFor(UUID id) {
        requireAgent(id);
        var children = agents.values().stream().filter(a -> id.equals(parents.get(a.id())))
                .map(a -> Map.of("id", a.id().toString(), "name", a.name(), "role", a.profile().role(),
                        "capabilities", a.capabilities().all().stream().map(Capability::name).toList())).toList();
        return Map.of("parentId", parents.containsKey(id) ? parents.get(id).toString() : "", "children", children,
                "peers", agents.values().stream().filter(a -> !a.id().equals(id))
                        .map(a -> Map.of("id", a.id(), "name", a.name(), "role", a.profile().role(),
                                "capabilities", a.capabilities().all().stream().map(Capability::name).toList())).toList());
    }
    public synchronized Map<UUID, UUID> hierarchy() { return Map.copyOf(parents); }
    public synchronized UUID delegate(UUID parent, UUID child, Goal goal) {
        Objects.requireNonNull(goal, "goal");
        if (!Objects.equals(parents.get(child), parent)) throw new IllegalArgumentException("Delegation requires a direct child");
        requireAgent(parent); requireAgent(child);
        Delegation task = new Delegation(UUID.randomUUID(), parent, child, goal, Delegation.Status.PENDING, "");
        delegations.put(task.id(), task);
        submitGoal(child, goal);
        trace.publish(new ExecutionEvent(null, ExecutionEventType.TASK_DELEGATED, parent, "organization",
                goal.description(), Map.of("delegationId", task.id(), "child", child, "goalId", goal.id())));
        return task.id();
    }
    private synchronized void finishDelegations() {
        for (var entry : List.copyOf(delegations.entrySet())) {
            Delegation task = entry.getValue();
            AbstractAgent child = agents.get(task.childId());
            if (task.status() != Delegation.Status.PENDING || child == null
                    || !child.processedGoals().contains(task.goal().id())) continue;
            Delegation.Status status = child.lastInvocationFailed() ? Delegation.Status.FAILED : Delegation.Status.SUCCEEDED;
            delegations.put(task.id(), task.finish(status, child.lastOutcome()));
            send(new AgentMessage(null, child.id(), task.parentId(), null, MessageType.RESPONSE, MessagePriority.NORMAL,
                    child.lastOutcome(), null, Map.of("delegationId", task.id().toString())));
            trace.publish(new ExecutionEvent(null, ExecutionEventType.TASK_COMPLETED, task.parentId(), "organization",
                    status + ": " + child.lastOutcome(), Map.of("delegationId", task.id(), "child", child.id())));
        }
    }
    public synchronized List<Delegation> delegations() { return List.copyOf(delegations.values()); }
    private void applyConfiguration(AbstractAgent agent) {
        AgentConfiguration config = agent.configuration();
        if (config != null && agent.memory() instanceof ModularMemory memory)
            memory.configure(config.memoryLimit(), config.contextMemory(), config.episodicMemory());
    }
    public synchronized UUID request(UUID requester, UUID recipient, Goal goal, String onResult) {
        requireAgent(requester); requireAgent(recipient); Objects.requireNonNull(goal);
        if (requester.equals(recipient)) throw new IllegalArgumentException("Peer requests require a different agent");
        // Fresh goal identity prevents caller reuse from matching an earlier completed goal.
        Goal work = new Goal(null, goal.description(), goal.priority());
        AgentRequest request = new AgentRequest(UUID.randomUUID(), requester, recipient, work,
                onResult == null ? "" : onResult, AgentRequest.Status.PENDING, "", Map.of(), environment.snapshot().facts());
        requests.put(request.id(), request);
        send(new AgentMessage(request.id(), requester, recipient, null, MessageType.REQUEST, MessagePriority.NORMAL,
                work.description(), null, Map.of("requestId", request.id().toString(), "goalId", work.id().toString(),
                        "priority", Integer.toString(work.priority()))));
        trace.publish(new ExecutionEvent(null, ExecutionEventType.REQUEST_SENT, requester, "requests",
                work.description(), Map.of("requestId", request.id(), "recipient", recipient)));
        return request.id();
    }
    public synchronized List<AgentRequest> requests() { return List.copyOf(requests.values()); }
    public synchronized List<AgentRequest> requestsFor(UUID actor) {
        return requests.values().stream().filter(r -> r.requesterId().equals(actor) || r.recipientId().equals(actor)).toList();
    }
    public synchronized AgentRequest requestResult(UUID actor, UUID id) {
        AgentRequest request = Optional.ofNullable(requests.get(id)).orElseThrow(() -> new IllegalArgumentException("Unknown request"));
        if (!request.requesterId().equals(actor) && !request.recipientId().equals(actor))
            throw new IllegalArgumentException("Only request participants may inspect its result");
        return request;
    }
    private synchronized void finishRequests() {
        for (AgentRequest request : List.copyOf(requests.values())) {
            AbstractAgent recipient = agents.get(request.recipientId());
            if (request.status() == AgentRequest.Status.PENDING && receivedRequests.contains(request.id())
                    && recipient != null && recipient.processedGoals().contains(request.goal().id()))
                completeRequest(request, recipient.lastInvocationFailed() ? AgentRequest.Status.FAILED
                        : AgentRequest.Status.SUCCEEDED, recipient.lastOutcome(), recipient.resultData());
        }
    }
    private synchronized void completeRequest(AgentRequest request, AgentRequest.Status status, String result) {
        completeRequest(request, status, result, Map.of());
    }
    private synchronized void completeRequest(AgentRequest request, AgentRequest.Status status, String result, Map<String, Object> outputs) {
        AgentRequest.CompletionEvidence evidence = request.finish(status, result, outputs).evidence();
        var contract = resultContracts.get(request.goal().description().strip().toLowerCase(Locale.ROOT));
        if (status == AgentRequest.Status.SUCCEEDED && contract != null) {
            Optional<String> failure = contract.apply(outputs);
            if (failure.isPresent()) {
                status = AgentRequest.Status.FAILED;
                evidence = AgentRequest.CompletionEvidence.FAILED;
                result = "Task result contract not met: " + failure.orElseThrow() + " | Invocation response: " + result;
            } else evidence = AgentRequest.CompletionEvidence.RESULT_VALIDATED;
        }
        AgentRequest completed = new AgentRequest(request.id(), request.requesterId(), request.recipientId(), request.goal(),
                request.onResult(), status, result, outputs, request.observedFacts(), evidence);
        requests.put(request.id(), completed);
        trace.publish(new ExecutionEvent(null, ExecutionEventType.REQUEST_COMPLETED, request.requesterId(), "requests",
                result, Map.of("requestId", request.id(), "recipient", request.recipientId(), "status", status, "completionEvidence", evidence, "outputs", outputs,
                        "providerStatus", agents.containsKey(request.recipientId()) ? agents.get(request.recipientId()).llmStatus() : "Recipient removed",
                        "recipientInvocationFailed", agents.containsKey(request.recipientId()) && agents.get(request.recipientId()).lastInvocationFailed())));
        if (agents.containsKey(request.requesterId())) {
            AgentMessage response = new AgentMessage(null, request.recipientId(), request.requesterId(), null,
                    MessageType.RESPONSE, MessagePriority.NORMAL, result, null,
                    Map.of("requestId", request.id().toString(), "status", status.name(), "completionEvidence", evidence.name()));
            // Runtime may report cancellation after the recipient has been removed.
            requestResponses.put(request.id(), response);
            route(response);
        }
    }
    private synchronized void cancelRequests(UUID removed) {
        for (AgentRequest request : List.copyOf(requests.values()))
            if (request.status() == AgentRequest.Status.PENDING &&
                    (request.requesterId().equals(removed) || request.recipientId().equals(removed)))
                {
                    AbstractAgent recipient = agents.get(request.recipientId());
                    if (recipient != null) recipient.cancelGoal(request.goal().id());
                    completeRequest(request, AgentRequest.Status.CANCELLED, "Agent removed");
                }
    }

    /** Host starts a bounded session. Every turn enters through the normal message mailbox. */
    public synchronized UUID startConversation(List<UUID> participants, String topic, int maxTurns) {
        if (participants == null || participants.size() != 2 || new HashSet<>(participants).size() != 2)
            throw new IllegalArgumentException("Select two different participants");
        if (topic == null || topic.isBlank() || topic.length() > 12000)
            throw new IllegalArgumentException("Enter a topic of 1..12000 characters");
        if (maxTurns < 1 || maxTurns > 20) throw new IllegalArgumentException("Agent turns must be 1..20");
        for (UUID id : participants) {
            if (collaborations.values().stream().anyMatch(c -> c.active() && (c.initiator.equals(id) || c.reviewer.equals(id))))
                throw new IllegalArgumentException("Participant already has active collaborative work");
            AbstractAgent agent = requireAgent(id);
            if (!agent.supportsConversation()) throw new IllegalArgumentException("Participant must support conversational replies: " + agent.name());
            if (!agent.isExecutable()) throw new IllegalArgumentException("Resume participant first: " + agent.name());
            if (conversations.values().stream().anyMatch(c -> c.status == ConversationSession.Status.RUNNING && c.participants.contains(id)))
                throw new IllegalArgumentException("Participant already has an active conversation");
        }
        ConversationSession session = new ConversationSession(participants, topic.strip(), maxTurns);
        conversations.put(session.id, session);
        conversationEvent(session, ExecutionEventType.CONVERSATION_STARTED, "Conversation started");
        queueConversationMessage(session, null, participants.getFirst(), topic.strip(), MessageType.CONVERSATION);
        return session.id;
    }
    public synchronized List<ConversationSession.Snapshot> conversations() {
        return conversations.values().stream().map(ConversationSession::snapshot).toList();
    }
    public synchronized ConversationSession.Snapshot conversation(UUID id) { return requireConversation(id).snapshot(); }
    public synchronized boolean hasActiveConversations() {
        return conversations.values().stream().anyMatch(c -> c.status == ConversationSession.Status.RUNNING);
    }
    private ConversationSession requireConversation(UUID id) {
        return Optional.ofNullable(conversations.get(id)).orElseThrow(() -> new IllegalArgumentException("Unknown conversation"));
    }
    /** Non-blocking cancellation: an admitted provider call may finish, but cannot schedule another turn. */
    public synchronized void cancelConversation(UUID id) {
        endConversation(requireConversation(id), ConversationSession.Status.CANCELLED, "Cancelled by user");
    }
    private void endConversation(ConversationSession session, ConversationSession.Status status, String detail) {
        if (session.status != ConversationSession.Status.RUNNING) return;
        session.status = status; session.detail = detail;
        cleanupRequested = true;
        conversationEvent(session, switch (status) {
            case COMPLETED -> ExecutionEventType.CONVERSATION_COMPLETED;
            case FAILED -> ExecutionEventType.CONVERSATION_FAILED;
            case CANCELLED -> ExecutionEventType.CONVERSATION_CANCELLED;
            default -> throw new IllegalArgumentException("Expected terminal status");
        }, detail);
    }
    private void conversationEvent(ConversationSession session, ExecutionEventType type, String detail) {
        trace.publish(new ExecutionEvent(null, type, null, "conversation", detail,
                Map.of("conversationId", session.id, "turns", session.completedTurns, "status", session.status)));
    }
    private void queueConversationMessage(ConversationSession session, UUID sender, UUID recipient, String content, MessageType type) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("conversationId", session.id.toString()); metadata.put("sequence", Integer.toString(session.completedTurns));
        metadata.put("senderName", sender == null ? "User" : requireAgent(sender).name());
        metadata.put("topic", session.topic); metadata.put("maxTurns", Integer.toString(session.maxTurns));
        if (sender != null) {
            var status = requireAgent(sender).llmStatus();
            metadata.put("provider", status.effectiveProvider()); metadata.put("model", status.model()); metadata.put("mode", status.mode());
        }
        AgentMessage message = new AgentMessage(null, sender, recipient, null, type, MessagePriority.NORMAL, content, null, metadata);
        session.messages.add(message); session.pending = message; session.delivered = false;
        if (sender != null) requireAgent(sender).memory().remember(MemoryEntry.message(message, message.createdAt()));
        route(message);
        conversationEvent(session, ExecutionEventType.CONVERSATION_TURN, metadata.get("senderName") + ": " + content);
    }
    /** Only the coordinator's exact pending envelope may activate a conversation turn. */
    private synchronized boolean acceptConversationMessage(AgentMessage message) {
        ConversationSession session = conversations.values().stream()
                .filter(c -> c.pending != null && c.pending.equals(message)).findFirst().orElse(null);
        if (session == null || session.status != ConversationSession.Status.RUNNING || session.delivered) return false;
        session.delivered = true;
        return true;
    }
    private synchronized void conversationMessageDelivered(AgentMessage message) {
        if (message.type() != MessageType.RESPONSE) return;
        ConversationSession session = requireConversation(UUID.fromString(message.metadata().get("conversationId")));
        endConversation(session, ConversationSession.Status.COMPLETED, "Turn limit reached; final reply delivered");
    }
    /** Called only under executionLock, outside provider invocations. */
    private synchronized void maintainConversations() {
        for (ConversationSession session : conversations.values()) {
            if (session.status == ConversationSession.Status.RUNNING) {
                for (UUID id : session.participants) {
                    AbstractAgent agent = agents.get(id);
                    if (agent == null || !agent.isExecutable() || !agent.supportsConversation()) {
                        endConversation(session, ConversationSession.Status.FAILED, "Participant unavailable, paused, stopped or failed: " + id);
                        break;
                    }
                }
            }
            if (session.status != ConversationSession.Status.RUNNING && session.pending != null) {
                AbstractAgent recipient = agents.get(session.pending.recipientId());
                if (recipient != null) recipient.cancelGoal(session.pending.id());
            }
        }
    }
    private synchronized void finishConversations() {
        for (ConversationSession session : conversations.values()) {
            if (session.status != ConversationSession.Status.RUNNING || !session.delivered) continue;
            AbstractAgent agent = agents.get(session.pending.recipientId());
            if (agent == null || !agent.processedGoals().contains(session.pending.id())) continue;
            if (agent.lastInvocationFailed() || agent.lastOutcome().isBlank()) {
                endConversation(session, ConversationSession.Status.FAILED, agent.name() + ": " + agent.lastOutcome());
                continue;
            }
            session.completedTurns++;
            int next = (session.participants.indexOf(agent.id()) + 1) % session.participants.size();
            queueConversationMessage(session, agent.id(), session.participants.get(next), agent.lastOutcome(),
                    session.completedTurns < session.maxTurns ? MessageType.CONVERSATION : MessageType.RESPONSE);
        }
        maintainConversations();
    }

    public void start() { running = true; trace.publish(ExecutionEvent.of(ExecutionEventType.RUNTIME_STARTED, null, "runtime", "Runtime started")); }
    public synchronized void stop() {
        running = false;
        conversations.values().forEach(c -> endConversation(c, ConversationSession.Status.CANCELLED, "Runtime stopped"));
        collaborations.values().forEach(c -> endCollaboration(c, CollaborationTask.Status.CANCELLED, "Runtime stopped"));
        trace.publish(ExecutionEvent.of(ExecutionEventType.RUNTIME_STOPPED, null, "runtime", "Runtime stopped"));
    }
    public boolean isRunning() { return running; }
    public long tickCount() { return tick.get(); }
    public long messagesProcessed() { return messagesProcessed.get(); }
    public ExecutionTrace trace() { return trace; }
    public EnvironmentView environment() { return environment; }
    public record EnvironmentInspection(WorldStateSnapshot snapshot, List<EnvironmentManagement.Fact> facts, boolean configurable, String restriction) {
        public EnvironmentInspection { facts = List.copyOf(facts); }
    }
    private String environmentEditRestriction() {
        if (running) return "Stop automatic cycles before editing facts";
        if (hasActiveConversations() || hasActiveCollaborations() || agents().stream().anyMatch(a -> !a.pendingGoals().isEmpty() || a.status() == AgentStatus.RUNNING)
                || requests.values().stream().anyMatch(r -> r.status() == AgentRequest.Status.PENDING))
            return "Finish or cancel pending agent/session work before editing facts";
        return "";
    }
    /** Host inspection is serialized with agent invocations; callers should use a background worker. */
    public EnvironmentInspection inspectEnvironment() {
        synchronized (executionLock) {
            synchronized (this) {
                var snapshot = environment.snapshot();
                if (environment instanceof EnvironmentManagement management)
                    return new EnvironmentInspection(snapshot, management.configurationFacts(), true, environmentEditRestriction());
                var facts = snapshot.facts().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new EnvironmentManagement.Fact(
                        e.getKey(), EnvironmentManagement.FactType.of(e.getValue()), e.getValue(), false, false, "Adapter does not support host editing")).toList();
                return new EnvironmentInspection(snapshot, facts, false, "This environment supports observations only");
            }
        }
    }
    /** Explicit host operation, never an agent set_fact capability. Requires idle work and the optional management interface. */
    public void changeEnvironmentFact(EnvironmentManagement.Change change) {
        synchronized (executionLock) {
            synchronized (this) {
                if (!(environment instanceof EnvironmentManagement management)) throw new IllegalStateException("Environment does not support host configuration");
                String restriction = environmentEditRestriction(); if (!restriction.isEmpty()) throw new IllegalStateException(restriction);
                var before = environment.snapshot(); management.applyHostChange(change); var after = environment.snapshot();
                trace.publish(new ExecutionEvent(null, ExecutionEventType.HOST_ENVIRONMENT_CHANGE, null, "host-environment",
                        change.operation() + " fact " + change.name(), Map.of("origin", "HOST", "operation", change.operation(), "fact", change.name(),
                        "beforePresent", before.facts().containsKey(change.name()), "previousValue", before.facts().getOrDefault(change.name(), "<absent>"),
                        "afterPresent", after.facts().containsKey(change.name()), "newValue", after.facts().getOrDefault(change.name(), "<absent>"))));
            }
        }
    }
    public List<ToolSchema> environmentActions() { return environment.actions(); }
    public MessageBus messageBus() { return messageBus; }
    public boolean needsCleanup() { return cleanupRequested; }
    /** Clean cancelled session goals at a safe boundary without executing unrelated queued work. */
    public void cleanupSessions() {
        synchronized (executionLock) {
            synchronized (this) { maintainConversations(); maintainCollaborations(); cleanupRequested = false; }
        }
    }
    public boolean hasReadyWork() { return cleanupRequested || messageBus.hasPendingMessages() || agents().stream().anyMatch(AbstractAgent::hasPendingWork); }

    /** Explicit host-selected review workflow; reasoning and tools still run only inside agent ticks. */
    public synchronized UUID startCollaboration(UUID initiator, UUID reviewer, String task) {
        if (initiator.equals(reviewer)) throw new IllegalArgumentException("Select two different agents");
        if (task == null || task.isBlank() || task.length() > 12000) throw new IllegalArgumentException("Enter a task of 1..12000 characters");
        for (UUID id : List.of(initiator, reviewer)) {
            AbstractAgent agent = requireAgent(id);
            if (!agent.supportsConversation() || !agent.isExecutable()) throw new IllegalArgumentException("Select available LLM agents; resume failed agents first");
            if (agent.hasPendingWork() || conversations.values().stream().anyMatch(c -> c.status == ConversationSession.Status.RUNNING && c.participants.contains(id))
                    || collaborations.values().stream().anyMatch(c -> c.active() && (c.initiator.equals(id) || c.reviewer.equals(id))))
                throw new IllegalArgumentException("Participant already has pending work or an active session");
        }
        CollaborationTask work = new CollaborationTask(initiator, reviewer, task.strip());
        Goal goal = Goal.of("Prepare an initial proposal for this task. The runtime will send your actual proposal to the selected reviewer, then return their feedback for your revision. "
                + "Do not send or request additional peer work in this stage.\nUser task:\n" + work.task);
        work.goalId = goal.id(); collaborations.put(work.id, work);
        submitGoal(initiator, goal); collaborationEvent(work); return work.id;
    }
    public synchronized List<CollaborationTask.Snapshot> collaborations() { return collaborations.values().stream().map(CollaborationTask::snapshot).toList(); }
    public synchronized boolean hasActiveCollaborations() { return collaborations.values().stream().anyMatch(CollaborationTask::active); }
    public synchronized void cancelCollaboration(UUID id) {
        CollaborationTask work = Optional.ofNullable(collaborations.get(id)).orElseThrow(() -> new IllegalArgumentException("Unknown collaboration"));
        endCollaboration(work, CollaborationTask.Status.CANCELLED, "Cancelled by user; already executed tool effects remain");
    }
    private synchronized CollaborationTask collaborationForRequest(UUID id) {
        return collaborations.values().stream().filter(c -> id.equals(c.requestId)).findFirst().orElse(null);
    }
    private void collaborationEvent(CollaborationTask work) {
        trace.publish(new ExecutionEvent(null, ExecutionEventType.COLLABORATION_CHANGED, work.initiator, "collaboration",
                work.status + ": " + (work.active() ? work.task : work.result), Map.of("collaborationId", work.id,
                "reviewer", work.reviewer, "status", work.status, "requestId", String.valueOf(work.requestId))));
    }
    private synchronized void endCollaboration(CollaborationTask work, CollaborationTask.Status status, String result) {
        if (!work.active()) return;
        cleanupRequested = true;
        work.status = status; work.result = result; work.finished = java.time.Instant.now(); collaborationEvent(work);
    }
    private synchronized void maintainCollaborations() {
        for (CollaborationTask work : collaborations.values()) {
            if (work.active()) for (UUID id : List.of(work.initiator, work.reviewer)) {
                AbstractAgent agent = agents.get(id);
                if (agent == null || !agent.isExecutable() || !agent.supportsConversation()) {
                    endCollaboration(work, CollaborationTask.Status.FAILED, "Participant unavailable: " + (agent == null ? id : agent.name() + ": " + agent.lastOutcome()));
                    break;
                }
            }
            if (!work.active()) {
                AbstractAgent initiator = agents.get(work.initiator);
                if (initiator != null) initiator.cancelGoal(work.goalId);
                AgentRequest request = requests.get(work.requestId);
                if (request != null && request.status() == AgentRequest.Status.PENDING) {
                    AbstractAgent reviewer = agents.get(work.reviewer);
                    if (reviewer != null) reviewer.cancelGoal(request.goal().id());
                    completeRequest(request, AgentRequest.Status.CANCELLED, work.result);
                }
            }
        }
    }
    private synchronized void finishCollaborations() {
        for (CollaborationTask work : collaborations.values()) {
            if (!work.active() || work.status == CollaborationTask.Status.REVIEWING) continue;
            AbstractAgent initiator = agents.get(work.initiator);
            if (initiator == null || !initiator.processedGoals().contains(work.goalId)) continue;
            if (initiator.lastInvocationFailed() || initiator.lastOutcome().isBlank()) {
                endCollaboration(work, CollaborationTask.Status.FAILED, initiator.lastOutcome()); continue;
            }
            if (work.status == CollaborationTask.Status.REVISING) {
                endCollaboration(work, CollaborationTask.Status.COMPLETED, initiator.lastOutcome()); continue;
            }
            work.draft = initiator.lastOutcome();
            work.requestId = request(work.initiator, work.reviewer, Goal.of("Review this proposal. Identify a specific weakness and actionable improvement. Return your own assessment; do not request further peer work.\nUser task:\n"
                    + work.task + "\nActual proposal from " + initiator.name() + ":\n" + work.draft),
                    "Produce the revised final response to the user. Use the correlated peer result supplied below: address the reviewer's specific criticism and explain what you changed (or why you disagree). Do not request more peer work.\nOriginal user task:\n"
                    + work.task + "\nYour initial proposal:\n" + work.draft);
            work.status = CollaborationTask.Status.REVIEWING; collaborationEvent(work);
        }
        maintainCollaborations();
    }
}
