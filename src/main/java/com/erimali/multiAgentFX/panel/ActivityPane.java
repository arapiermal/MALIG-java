package com.erimali.multiAgentFX.panel;

import app.core.agent.AbstractAgent;
import app.core.runtime.*;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Readable projection of actual runtime work and events, not a second execution model. */
public final class ActivityPane extends VBox {
    private final RuntimeFacade facade;
    private final ComboBox<AbstractAgent> initiator = new ComboBox<>(), reviewer = new ComboBox<>();
    private final ListView<CollaborationTask.Snapshot> tasks = new ListView<>();
    private final ListView<ExecutionEvent> events = new ListView<>();
    private final TextArea outcome = readOnly(), details = readOnly();
    private final Label status = new Label(), providers = new Label();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private static TextArea readOnly() { var a = new TextArea(); a.setEditable(false); a.setWrapText(true); return a; }
    public ActivityPane(RuntimeFacade facade) {
        this.facade = facade; setSpacing(8); setPadding(new javafx.geometry.Insets(12));
        initiator.setId("work-initiator"); reviewer.setId("work-reviewer");
        StringConverter<AbstractAgent> names = new StringConverter<>() {
            public String toString(AbstractAgent a) { return a == null ? "" : a.name() + " [" + a.id().toString().substring(0, 8) + "]"; }
            public AbstractAgent fromString(String text) { throw new UnsupportedOperationException(); }
        };
        initiator.setConverter(names); reviewer.setConverter(names);
        initiator.setOnAction(e -> showProviders()); reviewer.setOnAction(e -> showProviders());
        TextArea task = new TextArea("Develop a short resource-management strategy. Ask the Reviewer to identify a weakness, then revise your recommendation using its feedback.");
        task.setId("work-task"); task.setPrefRowCount(3); task.setWrapText(true);
        Button collaborate = new Button("Start LLM peer review"), cancel = new Button("Cancel selected collaboration");
        collaborate.setId("work-start"); cancel.setId("work-cancel");
        collaborate.setOnAction(e -> {
            if (initiator.getValue() == null || reviewer.getValue() == null) { status.setText("Select two LLM agents. Create them in Agents or Conversations."); return; }
            collaborate.setDisable(true);
            facade.startCollaboration(initiator.getValue().id(), reviewer.getValue().id(), task.getText()).whenComplete((id, error) -> Platform.runLater(() -> {
                collaborate.setDisable(false); status.setText(error == null ? "Started user-directed draft → review → revision." : errorMessage(error));
                refresh();
                if (id != null) tasks.getItems().stream().filter(t -> t.id().equals(id)).findFirst().ifPresent(tasks.getSelectionModel()::select);
            }));
        });
        cancel.setOnAction(e -> { var selected = tasks.getSelectionModel().getSelectedItem(); if (selected != null) facade.cancelCollaboration(selected.id()); });
        tasks.setId("work-tasks"); outcome.setId("work-outcome"); events.setId("activity-events"); details.setId("activity-details");
        tasks.setPrefWidth(230);
        tasks.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            cancel.setDisable(b == null || !b.active()); showTask(b);
        });
        events.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(ExecutionEvent event, boolean empty) {
                super.updateItem(event, empty); setText(empty || event == null ? null : eventLabel(event));
            }
        });
        events.getSelectionModel().selectedItemProperty().addListener((o, a, event) -> {
            if (event != null) details.setText(event.occurredAt() + "\n" + eventLabel(event) + "\n\n" + event.message() + "\n\n"
                    + event.metadata().entrySet().stream().map(v -> v.getKey() + ": " + v.getValue()).collect(java.util.stream.Collectors.joining("\n\n")));
        });
        SplitPane work = new SplitPane(tasks, outcome); work.setDividerPositions(.27);
        SplitPane interaction = new SplitPane(events, details); interaction.setDividerPositions(.4);
        TabPane tabs = new TabPane(new Tab("Collaboration outcomes", work), new Tab("Interactions / tools / errors", interaction));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Label help = new Label("LLM-only peer review: draft → selected reviewer → revision. This specialized user-directed workflow is separate from general heterogeneous work in Tasks / Requests. Each stage uses its configured provider and grants; no autonomous peer selection is claimed."); help.setWrapText(true);
        providers.setWrapText(true); status.setWrapText(true);
        getChildren().addAll(help, new FlowPane(8, 6, new Label("Initiator"), initiator, new Label("Cooperator"), reviewer), providers, task,
                new FlowPane(8, 6, collaborate, cancel), status, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        facade.onEvent(e -> {
            if (refreshQueued.compareAndSet(false, true)) Platform.runLater(() -> { refreshQueued.set(false); refresh(); });
        });
        refresh();
    }
    private String errorMessage(Throwable e) { return "Failed: " + (e.getCause() == null ? e : e.getCause()).getMessage(); }
    private String name(Object id) {
        try { return facade.runtime().find(UUID.fromString(String.valueOf(id))).map(AbstractAgent::name).orElse(String.valueOf(id)); }
        catch (IllegalArgumentException ex) { return id == null ? "Runtime" : String.valueOf(id); }
    }
    private String eventLabel(ExecutionEvent e) {
        String actor = name(e.agentId());
        if (e.type() == ExecutionEventType.MESSAGE_SENT) return actor + " → " + name(e.metadata().get("recipient")) + ": " + e.metadata().get("type");
        if (e.type() == ExecutionEventType.AGENT_RESPONSE) return actor + ": RESPONSE";
        return actor + ": " + e.type() + " | " + e.message().replace('\n', ' ').substring(0, Math.min(110, e.message().length()));
    }
    private void refresh() {
        var available = facade.runtime().agents().stream().filter(AbstractAgent::supportsConversation).toList();
        for (ComboBox<AbstractAgent> box : List.of(initiator, reviewer)) {
            UUID selected = box.getValue() == null ? null : box.getValue().id(); box.getItems().setAll(available);
            available.stream().filter(a -> a.id().equals(selected)).findFirst().ifPresent(box::setValue);
        }
        if (initiator.getValue() == null && !available.isEmpty()) initiator.setValue(available.getFirst());
        if (reviewer.getValue() == null && available.size() > 1) reviewer.setValue(available.get(1));
        UUID selected = tasks.getSelectionModel().getSelectedItem() == null ? null : tasks.getSelectionModel().getSelectedItem().id();
        tasks.getItems().setAll(facade.runtime().collaborations());
        tasks.getItems().stream().filter(t -> t.id().equals(selected)).findFirst().ifPresent(tasks.getSelectionModel()::select);
        if (tasks.getSelectionModel().isEmpty() && !tasks.getItems().isEmpty()) tasks.getSelectionModel().selectLast();
        var event = events.getSelectionModel().getSelectedItem();
        events.getItems().setAll(facade.recentEvents(500).stream().filter(e -> !Set.of(ExecutionEventType.ENVIRONMENT_TICK, ExecutionEventType.MEMORY_WRITE).contains(e.type())).toList());
        if (event != null) events.getSelectionModel().select(event);
        showProviders();
    }
    private void showProviders() {
        providers.setText(List.of(initiator, reviewer).stream().map(ComboBox::getValue).filter(Objects::nonNull)
                .map(a -> a.name() + " | " + a.status() + " | " + a.llmStatus()).collect(java.util.stream.Collectors.joining("\n")));
    }
    private void showTask(CollaborationTask.Snapshot t) {
        if (t == null) return;
        outcome.setText(t.status() + " | " + t.id() + "\nStarted: " + t.started() + "\nFinished: " + t.finished()
                + "\nRequest correlation: " + t.requestId() + "\n\nUSER TASK\n" + t.task()
                + "\n\n" + name(t.initiator()) + " → " + name(t.reviewer()) + ": DRAFT / REQUEST\n" + t.draft()
                + "\n\n" + name(t.reviewer()) + " → " + name(t.initiator()) + ": RESULT\n" + t.feedback()
                + "\n\n" + name(t.initiator()) + " → User: " + t.status() + "\n" + t.result());
    }
}
