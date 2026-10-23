package com.erimali.multiAgentFX.agenteditor;

import app.core.agent.*;
import app.core.capability.*;
import app.core.environment.AgentAction;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

public final class AgentRuntimePane extends VBox implements AutoCloseable {
    private final Label status = new Label();
    private final TextArea history = new TextArea();
    private final AutoCloseable subscription;
    public AgentRuntimePane(RuntimeFacade facade, UUID id) {
        setSpacing(10); setPadding(new javafx.geometry.Insets(10));
        history.setEditable(false); history.setWrapText(true);
        Button start = new Button("Resume"), pause = new Button("Pause"), stop = new Button("Stop"), refresh = new Button("Refresh");
        start.setOnAction(e -> facade.setAgentStatus(id, AgentStatus.IDLE));
        pause.setOnAction(e -> facade.setAgentStatus(id, AgentStatus.PAUSED));
        stop.setOnAction(e -> facade.setAgentStatus(id, AgentStatus.STOPPED));
        ComboBox<String> tool = new ComboBox<>();
        tool.setPromptText("Select a granted tool");
        GridPane arguments = FormLayout.createFormGrid();
        Map<String, TextField> inputs = new LinkedHashMap<>();
        tool.setOnAction(e -> {
            inputs.clear(); arguments.getChildren().clear();
            facade.findRuntimeAgent(id).flatMap(a -> a.capabilities().find(tool.getValue())).ifPresent(capability -> {
                Map<?, ?> properties = (Map<?, ?>) capability.schema().parametersSchema().get("properties");
                properties.forEach((key, value) -> {
                    TextField input = new TextField();
                    String type = String.valueOf(((Map<?, ?>) value).get("type"));
                    input.setPromptText(type);
                    inputs.put(key.toString(), input);
                    FormLayout.addRow(arguments, inputs.size() - 1, key + " (" + type + "):", input);
                });
            });
        });
        Button execute = new Button("Submit Human Proposal");
        execute.setOnAction(e -> {
            var agent = facade.findRuntimeAgent(id).orElse(null);
            if (!(agent instanceof HumanAgent human) || tool.getValue() == null) return;
            ToolSchema schema = human.capabilities().find(tool.getValue()).orElseThrow().schema();
            Map<?, ?> properties = (Map<?, ?>) schema.parametersSchema().get("properties");
            Map<String, Object> values = new LinkedHashMap<>();
            try {
                inputs.forEach((name, input) -> {
                    String text = input.getText();
                    if (text.isBlank() && !((java.util.List<?>) schema.parametersSchema().get("required")).contains(name)) return;
                    String type = String.valueOf(((Map<?, ?>) properties.get(name)).get("type"));
                    Object value = switch (type) {
                        case "integer", "number" -> Double.valueOf(text);
                        case "boolean" -> {
                            if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("Use true or false");
                            yield Boolean.valueOf(text);
                        }
                        default -> text;
                    };
                    values.put(name, value);
                });
                Optional<String> invalid = schema.validate(values);
                if (invalid.isPresent()) throw new IllegalArgumentException(invalid.get());
                facade.runAsync(() -> { human.propose(new AgentAction(id, schema.name(), values)); facade.drainWork(); });
            } catch (IllegalArgumentException error) {
                new Alert(Alert.AlertType.ERROR, error.getMessage()).showAndWait();
            }
        });
        Runnable update = () -> {
            var agent = facade.findRuntimeAgent(id).orElse(null);
            status.setText(agent == null ? "Not registered" : agent.status().name() + (agent.lastInvocationFailed() ? " | last task FAILED" : ""));
            execute.setDisable(!(agent instanceof HumanAgent));
            start.setDisable(agent == null); pause.setDisable(agent == null); stop.setDisable(agent == null);
            List<String> names = agent == null ? List.of() : agent.capabilities().all().stream().map(Capability::name).toList();
            if (!tool.getItems().equals(names)) {
                String selected = tool.getValue();
                tool.getItems().setAll(names);
                if (names.contains(selected)) tool.setValue(selected);
            }
            history.setText((agent == null ? "" : "Outcome: " + agent.lastOutcome() + "\nReasoning: " + agent.llmStatus() + "\nLast plan: " + agent.lastExecution() + "\n\n")
                    + facade.recentEvents(100).stream().filter(e -> id.equals(e.agentId()))
                    .map(e -> e.occurredAt() + " " + e.type() + ": " + e.message() + "\n" + e.metadata())
                    .collect(java.util.stream.Collectors.joining("\n")));
        };
        refresh.setOnAction(e -> update.run());
        subscription = facade.onEvent(e -> {
            if (id.equals(e.agentId()) || e.type() == app.core.runtime.ExecutionEventType.ENVIRONMENT_TICK)
                Platform.runLater(update);
        });
        getChildren().addAll(new HBox(8, new Label("Live status:"), status, start, pause, stop, refresh),
                new Label("Controls apply immediately. Resume permits queued work on the next tick."),
                new Label("Human agents: choose a tool and enter its typed arguments."), tool, arguments, execute, history);
        VBox.setVgrow(history, Priority.ALWAYS);
        update.run();
    }
    public ReadOnlyStringProperty statusTextProperty() { return status.textProperty(); }
    @Override public void close() {
        try { subscription.close(); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
