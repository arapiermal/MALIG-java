package com.erimali.multiAgentFX.panel;

import app.core.agent.AbstractAgent;
import app.core.runtime.ConversationSession;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;

/** A view of canonical runtime sessions. It never calls a model or generates a reply. */
public final class ConversationPane extends VBox {
    private final RuntimeFacade facade;
    private final ComboBox<AbstractAgent> first = new ComboBox<>(), second = new ComboBox<>();
    private final ComboBox<ConversationSession.Snapshot> sessions = new ComboBox<>();
    private final TextArea topic = new TextArea("Discuss whether resource allocation in a multi-agent system should be handled by deterministic algorithms or an LLM. Identify an appropriate hybrid solution.");
    private final TextArea transcript = new TextArea();
    private final Spinner<Integer> turns = new Spinner<>(1, 20, 3);
    private final Label status = new Label(), providers = new Label(), feedback = new Label();
    private final Button start = new Button("Start conversation"), cancel = new Button("Cancel conversation");
    private boolean refreshing;

    public ConversationPane(RuntimeFacade facade) {
        this.facade = facade; setSpacing(10); setPadding(new javafx.geometry.Insets(12));
        first.setId("conversation-first"); second.setId("conversation-second"); topic.setId("conversation-topic");
        turns.setId("conversation-turns"); start.setId("conversation-start"); cancel.setId("conversation-cancel");
        sessions.setId("conversation-sessions"); transcript.setId("conversation-transcript"); status.setId("conversation-status");
        StringConverter<AbstractAgent> names = new StringConverter<>() {
            public String toString(AbstractAgent a) { return a == null ? "" : a.name() + " [" + a.id().toString().substring(0, 8) + "]"; }
            public AbstractAgent fromString(String s) { throw new UnsupportedOperationException(); }
        };
        first.setConverter(names); second.setConverter(names);
        first.setPromptText("First speaker"); second.setPromptText("Second speaker");
        first.setOnAction(e -> showProviders()); second.setOnAction(e -> showProviders());
        topic.setPrefRowCount(3); topic.setWrapText(true);
        transcript.setEditable(false); transcript.setWrapText(true);
        providers.setWrapText(true); status.setWrapText(true); feedback.setWrapText(true);
        Button create = new Button("Create Analyst + Reviewer (API defaults)");
        Button applyDefaults = new Button("Apply API defaults to selected agents"); applyDefaults.setId("conversation-apply-defaults");
        applyDefaults.setOnAction(e -> {
            if (first.getValue() == null || second.getValue() == null) { feedback.setText("Select two LLM agents first."); return; }
            applyDefaults.setDisable(true);
            facade.applyConversationDefaults(List.of(first.getValue().id(), second.getValue().id())).whenComplete((ignored, error) -> Platform.runLater(() -> {
                applyDefaults.setDisable(false); feedback.setText(error == null ? "Provider/model/sampling defaults applied. Identities, instructions, memory and permissions preserved." : errorText(error)); refresh();
            }));
        });
        create.setOnAction(e -> {
            create.setDisable(true);
            facade.createConversationPair().whenComplete((ignored, error) -> Platform.runLater(() -> {
                create.setDisable(false); feedback.setText(error == null ? "Pair ready (existing preset reused). Check the effective settings below. API defaults only apply on creation; edit existing agents in Agents." : errorText(error));
                refresh();
            }));
        });
        start.setOnAction(e -> {
            if (first.getValue() == null || second.getValue() == null) { feedback.setText("Select two LLM agents."); return; }
            List<UUID> participants = List.of(first.getValue().id(), second.getValue().id());
            start.setDisable(true);
            facade.startConversation(participants, topic.getText(), turns.getValue()).whenComplete((id, error) -> Platform.runLater(() -> {
                start.setDisable(false);
                if (error != null) feedback.setText(errorText(error));
                else { feedback.setText("Session started. Turns advance on the runtime worker."); refresh(); selectSession(id); }
            }));
        });
        cancel.setOnAction(e -> {
            if (sessions.getValue() != null) { facade.cancelConversation(sessions.getValue().id()); refresh(); }
        });
        sessions.setOnAction(e -> { if (!refreshing) showSession(); });
        getChildren().addAll(new HBox(8, create, applyDefaults), new HBox(10, new Label("First"), first, new Label("Second"), second),
                providers, topic, new HBox(10, new Label("Maximum agent turns"), turns, start, cancel),
                new Label("Conversation sessions"), sessions, status, feedback, transcript,
                new Label("No tool grants needed. After a provider failure, inspect the error, wait/correct settings, then Start a new conversation. No resume is needed. Cancel discards late replies; no task or tool loop is automatically replayed."));
        VBox.setVgrow(transcript, Priority.ALWAYS);
        facade.onEvent(event -> Platform.runLater(this::refresh));
        refresh();
    }
    private String errorText(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause(); return "Failed: " + cause.getMessage();
    }
    private void refresh() {
        refreshing = true;
        try {
            var agents = facade.runtime().agents().stream().filter(AbstractAgent::supportsConversation).toList();
            UUID a = first.getValue() == null ? null : first.getValue().id(), b = second.getValue() == null ? null : second.getValue().id();
            first.getItems().setAll(agents); second.getItems().setAll(agents);
            agents.stream().filter(x -> x.id().equals(a)).findFirst().ifPresent(first::setValue);
            agents.stream().filter(x -> x.id().equals(b)).findFirst().ifPresent(second::setValue);
            if (first.getValue() == null && !agents.isEmpty()) first.setValue(agents.getFirst());
            if (second.getValue() == null && agents.size() > 1) second.setValue(agents.get(1));
            UUID selected = sessions.getValue() == null ? null : sessions.getValue().id();
            sessions.getItems().setAll(facade.runtime().conversations());
            if (selected != null) selectSession(selected);
            else if (!sessions.getItems().isEmpty()) sessions.setValue(sessions.getItems().getLast());
        } finally { refreshing = false; }
        showProviders(); showSession();
    }
    private void selectSession(UUID id) {
        sessions.getItems().stream().filter(s -> s.id().equals(id)).findFirst().ifPresent(sessions::setValue);
        showSession();
    }
    private void showProviders() {
        providers.setText(List.of(first, second).stream().map(ComboBox::getValue).filter(Objects::nonNull).map(a ->
                a.name() + " [" + a.status() + "]: configured " + a.configuration().llm().provider() + "/" + a.configuration().llm().model()
                        + " | temperature " + (a.configuration().llm().providerTemperature() ? "provider default" : a.configuration().llm().temperature())
                        + " | max tokens " + a.configuration().llm().maxTokens()
                        + "\nLast inference: " + a.llmStatus().effectiveProvider() + " | " + a.llmStatus().mode() + " | " + a.llmStatus().outcome() + " | " + a.llmStatus().detail())
                .collect(java.util.stream.Collectors.joining("\n")));
    }
    private void showSession() {
        var session = sessions.getValue(); cancel.setDisable(session == null || !session.active());
        if (session == null) { status.setText("No conversation yet"); return; }
        status.setText(session.status() + " — " + session.completedTurns() + "/" + session.maxTurns() + " agent turns. " + session.detail());
        String text = session.messages().stream().map(m -> "#" + m.metadata().get("sequence") + " " + m.metadata().get("senderName")
                + " [" + (m.senderId() == null ? "user" : m.senderId()) + "]"
                + (m.senderId() == null ? "" : " — " + m.metadata().get("provider") + "/" + m.metadata().get("model") + " " + m.metadata().get("mode"))
                + "\n" + m.content()).collect(java.util.stream.Collectors.joining("\n\n"));
        if (!transcript.getText().equals(text)) { transcript.setText(text); transcript.positionCaret(text.length()); }
    }
}
