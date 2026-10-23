package com.erimali.multiAgentFX.panel;

import app.core.agent.*;
import app.core.runtime.*;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** General host-directed work, independent of LLM peer review or a scenario. */
public final class AgentWorkPane extends VBox {
    private final RuntimeFacade facade;
    private final ComboBox<AbstractAgent> sender = new ComboBox<>(), recipient = new ComboBox<>();
    private final ListView<AgentRequest> requests = new ListView<>();
    private final TextArea details = new TextArea(), directory = new TextArea();
    private final AtomicBoolean queued = new AtomicBoolean();
    public AgentWorkPane(RuntimeFacade facade) {
        this.facade = facade; setSpacing(8); setPadding(new javafx.geometry.Insets(12));
        sender.setId("task-sender"); recipient.setId("task-recipient");
        StringConverter<AbstractAgent> names = new StringConverter<>() {
            public String toString(AbstractAgent a) { return a == null ? "" : a.name() + " — " + a.configuration().type(); }
            public AbstractAgent fromString(String value) { throw new UnsupportedOperationException(); }
        };
        sender.setConverter(names); recipient.setConverter(names);
        TextArea task = new TextArea("Calculate regional transfers"); task.setPrefRowCount(2); task.setWrapText(true); task.setId("task-content");
        TextField continuation = new TextField(); continuation.setPromptText("Optional requester task after response; must match its behavior. Empty = receive result only."); continuation.setId("task-continuation");
        Label status = new Label(); status.setWrapText(true);
        Button submit = new Button("Submit Task"), request = new Button("Request Agent Work"); submit.setId("task-submit"); request.setId("task-request");
        submit.setOnAction(e -> {
            if (sender.getValue() == null) { status.setText("Select an agent"); return; }
            status.setText("Submitted to the agent's configured controller; inspect its outcome below.");
            submit.setDisable(true); request.setDisable(true);
            facade.submitTask(sender.getValue().id(), task.getText()).whenComplete((v, error) -> Platform.runLater(() -> {
                submit.setDisable(false); request.setDisable(false);
                status.setText(error == null ? "Ready work processed. Human/paused work may be pending; unsupported work fails without disabling a healthy agent." : error(error)); refresh();
            }));
        });
        request.setOnAction(e -> {
            if (sender.getValue() == null || recipient.getValue() == null) { status.setText("Select requester and recipient"); return; }
            status.setText("Routing real peer work...");
            submit.setDisable(true); request.setDisable(true);
            facade.requestWork(sender.getValue().id(), recipient.getValue().id(), task.getText(), continuation.getText()).whenComplete((id, error) -> Platform.runLater(() -> {
                submit.setDisable(false); request.setDisable(false);
                status.setText(error == null ? "Request recorded: " + id : error(error)); refresh();
                if (id != null) requests.getItems().stream().filter(r -> r.id().equals(id)).findFirst().ifPresent(requests.getSelectionModel()::select);
            }));
        });
        details.setEditable(false); details.setWrapText(true); details.setId("task-result");
        directory.setEditable(false); directory.setWrapText(true); directory.setPrefRowCount(5); directory.setId("task-directory");
        sender.setOnAction(e -> showDirectory()); recipient.setOnAction(e -> showDirectory());
        requests.setCellFactory(list -> new ListCell<>() {
            protected void updateItem(AgentRequest r, boolean empty) { super.updateItem(r, empty); setText(empty || r == null ? null : name(r.requesterId()) + " → " + name(r.recipientId()) + " | " + r.outcomeLabel()); }
        });
        requests.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            if (r != null) details.setText("Request " + r.id() + " | " + r.outcomeLabel() + "\n" + name(r.requesterId()) + " → " + name(r.recipientId())
                    + "\nInvocation status: " + r.status() + " (provider status is shown separately below)\n\nTASK\n" + r.goal().description() + "\n\nRESULT\n" + r.result() + "\n\nSTRUCTURED OUTPUTS\n" + r.outputs()
                    + "\n\nOBSERVATIONS AT REQUEST\n" + r.observedFacts() + "\n\nCONTINUATION\n" + r.onResult() + "\n\nLatest recipient inference (may be newer than this request; see REQUEST_COMPLETED for the recorded status):\n" + facade.runtime().find(r.recipientId()).map(a2 -> a2.llmStatus().toString()).orElse("Recipient removed"));
        });
        SplitPane outcomes = new SplitPane(requests, details); outcomes.setDividerPositions(.35);
        Label help = new Label("General work supports deterministic, planner, LLM and human controllers. Role is descriptive; behavior/planner and granted tools determine supported work. Human requests wait for a typed proposal in Agents → Edit → Runtime / History. Selecting peers here is user-directed; LLMs can discover/select peers with granted list_agents/request_agent tools."); help.setWrapText(true);
        getChildren().addAll(help, new FlowPane(8, 6, new Label("Agent / requester"), sender, new Label("Recipient"), recipient), directory, task, continuation,
                new HBox(8, submit, request), status, outcomes);
        VBox.setVgrow(outcomes, Priority.ALWAYS);
        facade.onEvent(e -> { if (queued.compareAndSet(false, true)) Platform.runLater(() -> { queued.set(false); refresh(); }); }); refresh();
    }
    private String error(Throwable e) { return "Failed: " + (e.getCause() == null ? e : e.getCause()).getMessage(); }
    private String name(UUID id) { return facade.runtime().find(id).map(AbstractAgent::name).orElse(id.toString()); }
    private void showDirectory() {
        directory.setText(List.of(sender, recipient).stream().map(ComboBox::getValue).filter(Objects::nonNull).distinct().map(a -> a.name() + " | " + a.status() + " | " + a.id()
                + "\nRole: " + a.profile().role() + "\n" + a.profile().description() + "\nController: " + a.configuration().type()
                + " | policy: " + a.configuration().policy() + " " + a.configuration().policyOptions() + " | planner: " + a.configuration().planner()
                + "\nGranted tools: " + a.capabilities().all().stream().map(c -> c.name() + " — " + c.schema().description()).toList()
                + "\nLast outcome: " + a.lastOutcome()).collect(java.util.stream.Collectors.joining("\n\n")));
    }
    private void refresh() {
        var agents = List.copyOf(facade.runtime().agents());
        for (var box : List.of(sender, recipient)) {
            UUID id = box.getValue() == null ? null : box.getValue().id(); box.getItems().setAll(agents);
            agents.stream().filter(a -> a.id().equals(id)).findFirst().ifPresent(box::setValue);
        }
        if (sender.getValue() == null && !agents.isEmpty()) sender.setValue(agents.getFirst());
        if (recipient.getValue() == null && agents.size() > 1) recipient.setValue(agents.get(1));
        var selected = requests.getSelectionModel().getSelectedItem(); requests.getItems().setAll(facade.runtime().requests());
        if (selected != null) requests.getItems().stream().filter(r -> r.id().equals(selected.id())).findFirst().ifPresent(requests.getSelectionModel()::select);
        if (requests.getSelectionModel().isEmpty() && !requests.getItems().isEmpty()) requests.getSelectionModel().selectLast();
        showDirectory();
    }
}
