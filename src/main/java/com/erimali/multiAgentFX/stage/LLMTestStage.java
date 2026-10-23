package com.erimali.multiAgentFX.stage;

import app.llm.*;
import app.llm.provider.LLMServiceFactory;
import com.erimali.multiAgentFX.bridge.LLMTestFacade;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import java.util.Map;

public final class LLMTestStage extends Stage {
    public LLMTestStage(Stage owner, LLMTestFacade facade) {
        initOwner(owner); setTitle("LLM Provider / HMAS Tool-Loop Test");
        ComboBox<String> provider = new ComboBox<>(); provider.getItems().setAll(LLMServiceFactory.providers());
        provider.setConverter(new StringConverter<>() {
            public String toString(String value) { return value == null ? "" : LLMServiceFactory.label(value); }
            public String fromString(String text) { return text; }
        });
        var defaults = LLMServiceFactory.defaults();
        provider.setValue(LLMServiceFactory.isOffline(defaults.provider()) ? "offline" : defaults.provider());
        CompatibleSettingsPane compatible = new CompatibleSettingsPane(); compatible.load(defaults.provider(), defaults.options());
        TextField model = new TextField(defaults.model());
        model.setDisable(LLMServiceFactory.isOffline(provider.getValue()));
        provider.setOnAction(e -> {
            model.setDisable(LLMServiceFactory.isOffline(provider.getValue()));
            model.setText(LLMServiceFactory.defaultModel(provider.getValue())); compatible.load(provider.getValue(), Map.of());
        });
        Spinner<Double> temperature = new Spinner<>(0, 2, 0.2, 0.1);
        CheckBox providerTemperature = new CheckBox("Provider default temperature"); providerTemperature.setSelected(defaults.providerTemperature());
        temperature.disableProperty().bind(model.disableProperty().or(providerTemperature.selectedProperty()));
        TextArea plainPrompt = new TextArea("In under 100 words, explain why a multi-agent system might combine deterministic algorithms and LLMs.");
        TextArea toolPrompt = new TextArea("Call inspect_environment, then call remember with content HMAS tool-loop verified, then summarize the tool results.");
        for (TextArea area : java.util.List.of(plainPrompt, toolPrompt)) { area.setPrefRowCount(4); area.setWrapText(true); }
        plainPrompt.setId("provider-prompt"); toolPrompt.setId("tool-prompt");
        TextArea output = new TextArea(), trace = new TextArea();
        for (TextArea area : java.util.List.of(output, trace)) { area.setEditable(false); area.setWrapText(true); }
        Label status = new Label("Not invoked"); status.setWrapText(true); status.setId("provider-test-status");
        output.setId("provider-test-response"); trace.setId("provider-test-trace");
        Button plain = new Button("Run Provider Prompt"), toolLoop = new Button("Run HMAS Tool Loop");
        plain.setId("provider-test-plain"); toolLoop.setId("provider-test-tools");
        java.util.function.Consumer<Boolean> run = withTools -> {
            String providerId = provider.getValue(), modelId = !LLMServiceFactory.isOffline(providerId) ? model.getText().strip() : "offline-rules";
            String text = (withTools ? toolPrompt : plainPrompt).getText().strip(); double temp = temperature.getValue();
            Map<String, String> settings = compatible.options(Map.of(LLMConfig.TEMPERATURE_MODE, providerTemperature.isSelected() ? "provider-default" : "explicit"));
            plain.setDisable(true); toolLoop.setDisable(true);
            output.clear(); trace.clear(); status.setText("IN_PROGRESS | requested " + providerId + " / " + modelId);
            Thread.startVirtualThread(() -> {
                try {
                    var result = facade.run(new LLMConfig(providerId, modelId, temp, 4096, settings), text, withTools);
                    Platform.runLater(() -> {
                        var s = result.provider();
                        status.setText(s.mode() + " | " + s.outcome() + " | agent " + result.status()
                                + "\nRequested: " + s.requestedProvider() + " | Effective: " + s.effectiveProvider() + " | Model: " + s.model() + "\n" + s.detail());
                        output.setText(result.response()); trace.setText(result.traceText());
                        plain.setDisable(false); toolLoop.setDisable(false);
                    });
                } catch (RuntimeException ex) {
                    Platform.runLater(() -> { status.setText("FAILED: " + ex.getMessage()); plain.setDisable(false); toolLoop.setDisable(false); });
                }
            });
        };
        plain.setOnAction(e -> run.accept(false)); toolLoop.setOnAction(e -> run.accept(true));
        Label textHelp = new Label("Ordinary text generation through an LLMAgent with no tool grants. Do not request function execution here. Unexpected model function calls fail visibly and are not executed."); textHelp.setWrapText(true);
        Label toolHelp = new Label("Authorized tools: inspect_environment and remember. Isolated empty environment. Up to three tool rounds / eight calls, then a reserved text-only completion. Uses the same runtime and provider factory as agents."); toolHelp.setWrapText(true);
        TabPane modes = new TabPane(new Tab("Provider Prompt — text only", new VBox(8, textHelp, plainPrompt, plain)),
                new Tab("HMAS Tool Loop", new VBox(8, toolHelp, toolPrompt, toolLoop)));
        modes.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        TabPane results = new TabPane(new Tab("Response / error", output), new Tab("Execution trace", trace));
        results.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox root = new VBox(10, new HBox(8, provider, model, temperature), providerTemperature, compatible, modes, status, results);
        root.setPadding(new javafx.geometry.Insets(12)); VBox.setVgrow(results, Priority.ALWAYS);
        setScene(new Scene(root, 950, 720));
    }
}
