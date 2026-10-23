package com.erimali.multiAgentFX.panel;

import app.llm.LLMConfig;
import app.llm.provider.LLMServiceFactory;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

/** Scenario configuration belongs at the application composition boundary, not in HMAS core. */
public final class ResourceDemoPane extends VBox {
    public ResourceDemoPane(RuntimeFacade facade) {
        setSpacing(10); setPadding(new javafx.geometry.Insets(12));
        Spinner<Integer> initial = new Spinner<>(0, 100, 65), target = new Spinner<>(0, 100, 80), threshold = new Spinner<>(0, 100, 80);
        initial.setId("resource-initial"); target.setId("resource-target"); threshold.setId("resource-threshold");
        TextField model = new TextField(LLMServiceFactory.isOffline(LLMServiceFactory.defaults().provider()) ? LLMServiceFactory.defaultModel("google") : LLMServiceFactory.defaults().model());
        model.setId("resource-model");
        Button live = new Button("Start Live Hybrid Demo (Google)"), offline = new Button("Start Offline Test Demo");
        live.setId("resource-live"); offline.setId("resource-offline");
        Label status = new Label("Ready. Each run creates a monitor, analyst and planner in the real runtime."); status.setWrapText(true); status.setId("resource-status");
        TextArea result = new TextArea(); result.setEditable(false); result.setWrapText(true); result.setId("resource-result");
        java.util.function.Consumer<Boolean> start = online -> {
            int before = initial.getValue(), desired = target.getValue(), trigger = threshold.getValue();
            var config = new LLMConfig(online ? "google" : "offline", online ? model.getText().strip() : "offline-rules", .2, 4096, LLMServiceFactory.defaults().options());
            var existing = facade.runtime().requests().stream().map(r -> r.id()).collect(java.util.stream.Collectors.toSet());
            live.setDisable(true); offline.setDisable(true);
            status.setText("Running " + LLMServiceFactory.label(config.provider()) + ". Inspect Activity for inference, routing and tool outcomes.");
            result.clear();
            facade.runResourceDemo(before, desired, trigger, config).whenComplete((ignored, error) -> Platform.runLater(() -> {
                live.setDisable(false); offline.setDisable(false);
                if (error != null) { status.setText("Failed: " + (error.getCause() == null ? error : error.getCause()).getMessage()); return; }
                var requests = facade.runtime().requests().stream().filter(r -> !existing.contains(r.id())).toList();
                boolean failed = requests.stream().anyMatch(r -> r.status() != app.core.runtime.AgentRequest.Status.SUCCEEDED);
                status.setText((failed ? "Stopped with failed/pending work" : "Completed") + " | " + LLMServiceFactory.label(config.provider())
                        + " | resources " + before + " → " + facade.runtime().environment().snapshot().facts().get("resourceLevel"));
                result.setText("Configured initial=" + before + ", target=" + desired + ", trigger: level < " + trigger
                        + "\nFinal environment: " + facade.runtime().environment().snapshot().summary()
                        + (requests.isEmpty() ? "\nNo request: monitor threshold was not crossed." : "")
                        + requests.stream().map(r -> "\n\n" + r.id() + " | " + r.status() + "\n"
                            + facade.runtime().find(r.requesterId()).map(a -> a.name()).orElse("removed") + " → "
                            + facade.runtime().find(r.recipientId()).map(a -> a.name()).orElse("removed")
                            + "\nTASK: " + r.goal().description() + "\nRESULT: " + r.result() + "\nSTRUCTURED ADVICE: " + r.outputs()
                            + "\nPROVIDER: " + facade.runtime().find(r.recipientId()).map(a -> a.llmStatus().toString()).orElse("removed"))
                            .collect(java.util.stream.Collectors.joining("")));
            }));
        };
        live.setOnAction(e -> start.accept(true)); offline.setOnAction(e -> start.accept(false));
        Label help = new Label("The monitor requests advice only below the threshold. Valid intervention=true advice permits the deterministic planner to reach the target; false, missing, invalid or failed advice does not. Try initial=85, target=80, threshold=90 for a non-intervention case. Google never falls back to offline. Stop automatic cycles and finish pending work before resetting the environment."); help.setWrapText(true);
        getChildren().addAll(help, new FlowPane(8, 6, new Label("Initial"), initial, new Label("Target"), target, new Label("Threshold"), threshold),
                new HBox(8, new Label("Live model"), model), new FlowPane(8, 6, live, offline), status, result);
        VBox.setVgrow(result, Priority.ALWAYS);
        if (!facade.supportsResourceDemo()) { live.setDisable(true); offline.setDisable(true); status.setText("The active scenario does not offer the resource demo."); }
    }
}
