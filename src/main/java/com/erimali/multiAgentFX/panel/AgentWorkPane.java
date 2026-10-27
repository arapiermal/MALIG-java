package com.erimali.multiAgentFX.panel;

import app.core.agent.*;
import app.core.runtime.*;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Views real submitted goals and peer work. Scheduling and all effects remain in the runtime. */
public final class AgentWorkPane extends VBox {
    private final RuntimeFacade facade;
    private final ObservableList<AbstractAgent> agents = FXCollections.observableArrayList();
    private final ComboBox<AbstractAgent> sender = new ComboBox<>(agents), requester = new ComboBox<>(agents), recipient = new ComboBox<>(agents);
    private final ListView<UserTask> tasks = new ListView<>();
    private final ListView<AgentRequest> requests = new ListView<>();
    private final TextArea task = input("task-content"), peerTask = input("request-content"), details = readOnly("task-result"), directory = readOnly("task-directory");
    private final TextField continuation = new TextField();
    private final Button submit = new Button("Submit Task"), request = new Button("Request Agent Work"), cancel = new Button("Cancel pending task");
    private final Button cancelWait = new Button("Cancel selected request's wait set");
    private final Label taskStatus = label(""), requestStatus = label("");
    private final TabPane modes = new TabPane();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Map<UUID, String> names = new HashMap<>();
    private boolean submitting, requesting;

    public AgentWorkPane(RuntimeFacade facade) {
        this.facade = facade; setSpacing(8); setPadding(new javafx.geometry.Insets(12));
        sender.setId("task-sender"); requester.setId("request-sender"); recipient.setId("task-recipient");
        StringConverter<AbstractAgent> converter = new StringConverter<>() {
            public String toString(AbstractAgent a) { return a == null ? "" : a.name() + " [" + a.id().toString().substring(0, 8) + "] - " + a.configuration().type(); }
            public AbstractAgent fromString(String value) { throw new UnsupportedOperationException(); }
        };
        for (var box : List.of(sender, requester, recipient)) {
            box.setConverter(converter); box.setMaxWidth(Double.MAX_VALUE);
            box.setOnAction(e -> { updateButtons(); showDirectory(); showDetails(); });
        }
        task.setPromptText("Describe work supported by this agent's configured controller and granted tools.");
        peerTask.setPromptText("Enter the actual work and context to send to the recipient.");
        continuation.setId("task-continuation"); continuation.setPromptText("Optional continuation goal for the requester after the correlated response");
        submit.setId("task-submit"); request.setId("task-request"); cancel.setId("task-cancel");
        cancelWait.setId("request-cancel-wait");
        cancelWait.setOnAction(e -> selectedWait().ifPresent(w -> facade.cancelRequestWait(w.id())));
        taskStatus.setId("task-status"); requestStatus.setId("request-status");
        tasks.setId("task-history"); requests.setId("request-history"); modes.setId("task-modes");
        submit.setOnAction(e -> submit()); request.setOnAction(e -> request());
        cancel.setOnAction(e -> {
            var selected = tasks.getSelectionModel().getSelectedItem(); if (selected == null) return;
            facade.cancelPendingTask(selected.id()).whenComplete((cancelled, error) -> Platform.runLater(() -> {
                taskStatus.setText(error != null ? error(error) : Boolean.TRUE.equals(cancelled) ? "Pending goal cancelled. No replay." : "Task already started or finished; in-flight effects cannot be undone."); refresh();
            }));
        });
        task.textProperty().addListener((o,a,b) -> updateButtons()); peerTask.textProperty().addListener((o,a,b) -> updateButtons());
        tasks.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(UserTask t, boolean empty) { super.updateItem(t, empty); setText(empty || t == null ? null : t.agentName() + " | " + t.status() + "\n" + shortText(t.goal().description())); }
        });
        requests.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AgentRequest r, boolean empty) { super.updateItem(r, empty); setText(empty || r == null ? null : name(r.requesterId()) + " -> " + name(r.recipientId()) + " | " + r.outcomeLabel() + "\n" + shortText(r.goal().description())); }
        });
        tasks.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> { updateButtons(); showDetails(); });
        requests.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> { updateButtons(); showDetails(); });
        VBox single = new VBox(8, label("Agent"), sender, task, new FlowPane(8,6,submit,cancel), taskStatus,
                label("One submitted invocation is recorded per goal ID. Human/paused work can remain pending.\nExplicitly grant list_agents + request_agent for dynamic peer discovery/work. For ONE continuation after multiple results, also grant await_agent_requests: omit individual onResult, await the returned request IDs, then evaluate every result. No grants are enabled automatically."),
                label("Submitted task history"), tasks);
        VBox peer = new VBox(8, label("Requester"), requester, label("Recipient"), recipient, peerTask,
                label("onResult is an optional new goal for the requester after the response, not a callback script. Leave blank to receive/inspect the result without invoking the requester again."),
                continuation, request, requestStatus, label("Runtime peer-request history (separate from parent-child delegation)"), requests, cancelWait,
                label("Cancelling a wait suppresses its continuation; peer work and effects already admitted remain. Stop ends automatic cycles, not generic peer work or an in-flight API request. Step can invoke several agents."));
        VBox.setVgrow(tasks, Priority.ALWAYS); VBox.setVgrow(requests, Priority.ALWAYS);
        modes.getTabs().setAll(new Tab("Single-Agent Task", single), new Tab("Peer Request", peer));
        modes.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        modes.getSelectionModel().selectedIndexProperty().addListener((o,a,b) -> { showDirectory(); showDetails(); });
        TitledPane info = new TitledPane("Selected agents: current configuration and granted capabilities", directory); info.setExpanded(false);
        directory.setPrefRowCount(5);
        VBox result = new VBox(6, label("Selected work: attributable result / errors"), details, info); VBox.setVgrow(details, Priority.ALWAYS);
        SplitPane workspace = new SplitPane(modes, result); workspace.setDividerPositions(.43); VBox.setVgrow(workspace, Priority.ALWAYS);
        getChildren().addAll(label("Submit work through the configured controller. Roles describe purpose; policies, planners and granted tools determine actual behavior. Invocation completion does not prove goal achievement."), workspace);
        facade.onEvent(e -> {
            if (!Set.of(ExecutionEventType.AGENT_CREATED, ExecutionEventType.AGENT_UPDATED, ExecutionEventType.AGENT_REMOVED,
                    ExecutionEventType.AGENT_STATE_CHANGED, ExecutionEventType.GOAL_SUBMITTED, ExecutionEventType.GOAL_STARTED,
                    ExecutionEventType.GOAL_COMPLETED, ExecutionEventType.GOAL_CANCELLED, ExecutionEventType.REQUEST_SENT,
                    ExecutionEventType.REQUEST_COMPLETED, ExecutionEventType.REQUEST_RESULT_RECEIVED, ExecutionEventType.REQUEST_WAIT_CHANGED).contains(e.type())) return;
            if (queued.compareAndSet(false, true)) Platform.runLater(() -> { queued.set(false); refresh(); });
        });
        refresh();
    }
    private static TextArea input(String id) { TextArea a = new TextArea(); a.setId(id); a.setWrapText(true); a.setPrefRowCount(3); return a; }
    private static TextArea readOnly(String id) { TextArea a = input(id); a.setEditable(false); return a; }
    private static Label label(String text) { Label l = new Label(text); l.setWrapText(true); return l; }
    private static String shortText(String text) { String flat = text.replace('\n',' '); return flat.substring(0,Math.min(90,flat.length())); }
    private static String error(Throwable e) { return "Not accepted: " + (e.getCause() == null ? e : e.getCause()).getMessage(); }
    private String name(UUID id) { return names.getOrDefault(id, id.toString()); }
    private boolean single() { return modes.getSelectionModel().getSelectedIndex() == 0; }
    private void submit() {
        if (submit.isDisabled()) return;
        submitting = true; updateButtons(); taskStatus.setText("Submitting on the runtime worker...");
        facade.submitTrackedTask(sender.getValue().id(), task.getText()).whenComplete((receipt,error) -> Platform.runLater(() -> {
            submitting = false; refresh();
            if (error != null) taskStatus.setText(error(error));
            else { taskStatus.setText("Accepted goal " + receipt.id() + "\n" + receipt.advancementNote()); selectTask(receipt.id()); }
            updateButtons();
        }));
    }
    private void request() {
        if (request.isDisabled()) return;
        requesting = true; updateButtons(); requestStatus.setText("Submitting peer work on the runtime worker...");
        facade.requestTrackedWork(requester.getValue().id(), recipient.getValue().id(), peerTask.getText(), continuation.getText()).whenComplete((receipt,error) -> Platform.runLater(() -> {
            requesting = false; refresh();
            if (error != null) requestStatus.setText(error(error));
            else { requestStatus.setText("Accepted request " + receipt.id() + " (submission is not task success)\n" + receipt.advancementNote()); selectRequest(receipt.id()); }
            updateButtons();
        }));
    }
    private void updateButtons() {
        submit.setDisable(submitting || sender.getValue() == null || task.getText().isBlank());
        request.setDisable(requesting || requester.getValue() == null || recipient.getValue() == null || peerTask.getText().isBlank()
                || requester.getValue().id().equals(recipient.getValue().id()));
        var t = tasks.getSelectionModel().getSelectedItem(); cancel.setDisable(t == null || t.status() != UserTask.Status.PENDING);
        cancelWait.setDisable(selectedWait().isEmpty());
    }
    private Optional<RequestWait> selectedWait() {
        var r = requests.getSelectionModel().getSelectedItem();
        return r == null ? Optional.empty() : facade.runtime().requestWaits().stream().filter(w -> w.requestIds().contains(r.id())
                && (w.status() == RequestWait.Status.WAITING || w.status() == RequestWait.Status.QUEUED)).findFirst();
    }
    private void selectTask(UUID id) { tasks.getItems().stream().filter(t -> t.id().equals(id)).findFirst().ifPresent(tasks.getSelectionModel()::select); showDetails(); }
    private void selectRequest(UUID id) { requests.getItems().stream().filter(r -> r.id().equals(id)).findFirst().ifPresent(requests.getSelectionModel()::select); showDetails(); }
    private static <T> void sync(ObservableList<T> existing, List<T> next) {
        for (int i=0; i<next.size(); i++) { if (i>=existing.size()) existing.add(next.get(i)); else if (!Objects.equals(existing.get(i),next.get(i))) existing.set(i,next.get(i)); }
        if (existing.size()>next.size()) existing.remove(next.size(),existing.size());
    }
    private void refresh() {
        var next = List.copyOf(facade.runtime().agents());
        if (!agents.equals(next)) {
            Map<ComboBox<AbstractAgent>, UUID> selected = new HashMap<>();
            for (var box:List.of(sender,requester,recipient)) if (box.getValue()!=null) selected.put(box,box.getValue().id());
            next.forEach(a -> names.put(a.id(),a.name())); sync(agents,next);
            for (var box:List.of(sender,requester,recipient)) box.setValue(next.stream().filter(a -> a.id().equals(selected.get(box))).findFirst().orElse(null));
            if (sender.getValue()==null && !agents.isEmpty()) sender.setValue(agents.getFirst());
            if (requester.getValue()==null && !agents.isEmpty()) requester.setValue(agents.getFirst());
            if (recipient.getValue()==null) agents.stream().filter(a -> requester.getValue()==null || !a.id().equals(requester.getValue().id())).findFirst().ifPresent(recipient::setValue);
            requests.refresh();
        }
        var selectedTask=tasks.getSelectionModel().getSelectedItem(); var selectedRequest=requests.getSelectionModel().getSelectedItem();
        sync(tasks.getItems(),facade.runtime().userTasks()); sync(requests.getItems(),facade.runtime().requests());
        if (selectedTask!=null) selectTask(selectedTask.id()); else if (!tasks.getItems().isEmpty()) tasks.getSelectionModel().selectLast();
        if (selectedRequest!=null) selectRequest(selectedRequest.id()); else if (!requests.getItems().isEmpty()) requests.getSelectionModel().selectLast();
        updateButtons(); showDirectory(); showDetails();
    }
    private void showDetails() {
        String value;
        if (single()) {
            var t=tasks.getSelectionModel().getSelectedItem();
            if (t==null) value="Submit a single-agent task, then select its history entry. Each result is bound to the actual goal ID.";
            else value="Goal ID: " + t.id() + "\nAgent: " + t.agentName() + " [" + t.agentId() + "]\nStatus: " + t.outcomeLabel()
                    + (t.terminal() ? "" : "\nCurrent agent lifecycle: " + facade.runtime().find(t.agentId()).map(a -> a.status().name()).orElse("removed"))
                    + "\nSubmitted: " + t.submittedAt() + "\nStarted: " + Objects.toString(t.startedAt(),"not started") + "\nFinished: " + Objects.toString(t.finishedAt(),"not finished")
                    + "\n\nTASK\n" + t.goal().description() + "\n\nRESULT / ERROR\n" + (t.result().isBlank()?"No result yet":t.result())
                    + "\n\nOUTPUT DATA\n" + t.outputs() + "\n\nProvider snapshot for this invocation:\n" + t.provider()
                    + "\n\nThis history covers this invocation only. Asynchronous peer work and later continuation invocations are separate; successful invocation is not verified goal achievement.";
        } else {
            var r=requests.getSelectionModel().getSelectedItem();
            if (r==null) value="Submit a peer request or select existing runtime peer work.";
            else value="Request ID: " + r.id() + "\nGoal ID: " + r.goal().id() + "\nRequester: " + name(r.requesterId()) + " [" + r.requesterId() + "]\nRecipient: " + name(r.recipientId()) + " [" + r.recipientId() + "]"
                    + "\nOutcome: " + r.outcomeLabel() + "\nRaw request status: " + r.status() + "\nCompletion evidence: " + r.evidence()
                    + "\nCorrelated response delivery: " + (facade.runtime().requestResponseDelivered(r.id())?"DELIVERED":"not delivered yet")
                    + "\n\nRECIPIENT WORK\n" + r.goal().description() + "\n\nCORRELATED RESPONSE / ERROR\n" + r.result()
                    + "\n\nOUTPUT DATA\n" + r.outputs() + "\n\nOBSERVATIONS AT REQUEST\n" + r.observedFacts() + "\n\nonResult continuation goal:\n" + (r.onResult().isBlank()?"None - result reception does not invoke the requester":r.onResult());
        }
        UUID owner = single() ? Optional.ofNullable(tasks.getSelectionModel().getSelectedItem()).map(UserTask::agentId).orElse(null)
                : Optional.ofNullable(requests.getSelectionModel().getSelectedItem()).map(AgentRequest::requesterId).orElse(null);
        var waits = facade.runtime().requestWaits().stream().filter(w -> w.ownerId().equals(owner)).toList();
        if (!waits.isEmpty()) value += "\n\nREQUEST WAIT SETS (COMPLETED means continuation invocation completed, not verified achievement)\n"
                + waits.stream().map(w -> "Wait " + w.id() + " | " + w.status() + "\nRequests: " + w.requestIds()
                        + "\nContinuation goal " + w.continuation().id() + ": " + w.continuation().description()
                        + "\nContinuation result / error: " + (w.result().isBlank() ? "No completed continuation result" : w.result())).collect(java.util.stream.Collectors.joining("\n\n"));
        if (!details.getText().equals(value)) details.setText(value);
    }
    private void showDirectory() {
        List<ComboBox<AbstractAgent>> boxes=single()?List.of(sender):List.of(requester,recipient);
        String value=boxes.stream().map(ComboBox::getValue).filter(Objects::nonNull).distinct().map(a -> a.name() + " [" + a.id() + "] | " + a.status()
                + "\nRole: " + a.profile().role() + "\n" + a.profile().description() + "\nController: " + a.configuration().type()
                + " | policy: " + a.configuration().policy() + " " + a.configuration().policyOptions() + " | planner: " + a.configuration().planner()
                + "\nGranted capabilities: " + a.capabilities().all().stream().map(c -> c.name()+": "+c.schema().description()).toList()).collect(java.util.stream.Collectors.joining("\n\n"));
        if (!directory.getText().equals(value)) directory.setText(value);
    }
}
