package com.erimali.multiAgentFX.panel;

import app.core.environment.WorldEntity;
import app.core.runtime.*;
import app.llm.LLMConfig;
import com.erimali.multiAgentFX.stage.CompatibleSettingsPane;
import app.llm.provider.LLMServiceFactory;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tables and settings over the scenario's authoritative snapshots. No planning or transfer logic here. */
public final class RegionalDemoPane extends VBox {
    private final RuntimeFacade facade;
    private final TableView<WorldEntity> table = new TableView<>();
    private final TextArea outcomes = new TextArea();
    private final Label state = new Label();
    private volatile Map<String, Map<String, Object>> initial = Map.of();
    private Set<UUID> precedingRequests = Set.of();
    private final AtomicBoolean queued = new AtomicBoolean();
    public RegionalDemoPane(RuntimeFacade facade) {
        this.facade = facade; setSpacing(8); setPadding(new javafx.geometry.Insets(12));
        Spinner<Integer> food = new Spinner<>(0, 80, 30), energy = new Spinner<>(0, 70, 25), transport = new Spinner<>(0, 100, 20);
        food.setId("regional-food"); energy.setId("regional-energy"); transport.setId("regional-transport");
        ComboBox<String> selection = new ComboBox<>(); selection.getItems().setAll("food", "energy", "none", "llm"); selection.setValue("food"); selection.setId("regional-selection");
        selection.setConverter(new StringConverter<>() {
            public String toString(String value) { return value == null ? "" : switch (value) { case "google", "llm" -> "Online LLM — choose competing priorities"; case "none" -> "Deterministic — non-intervention"; default -> "Deterministic — prioritize " + value; }; }
            public String fromString(String s) { return s; }
        });
        var defaults = LLMServiceFactory.defaults();
        ComboBox<String> provider = new ComboBox<>(); provider.setId("regional-provider");
        provider.getItems().setAll(LLMServiceFactory.providers().stream().filter(p -> !LLMServiceFactory.isOffline(p)).toList());
        provider.setValue(LLMServiceFactory.isOffline(defaults.provider()) ? "google" : defaults.provider());
        TextField model = new TextField(LLMServiceFactory.defaultModel(provider.getValue())); model.setId("regional-model");
        CompatibleSettingsPane compatible = new CompatibleSettingsPane();
        compatible.load(provider.getValue(), defaults.provider().equals(provider.getValue()) ? defaults.options() : LLMServiceFactory.defaultOptions(provider.getValue()));
        CheckBox defaultTemperature = new CheckBox("Provider-default temperature"); defaultTemperature.setSelected(defaults.providerTemperature());
        Spinner<Double> temperature = new Spinner<>(0, 2, defaults.temperature(), .1);
        temperature.disableProperty().bind(defaultTemperature.selectedProperty());
        Spinner<Integer> maxTokens = new Spinner<>(1, Integer.MAX_VALUE, defaults.maxTokens(), 256); maxTokens.setEditable(true);
        provider.setOnAction(e -> { model.setText(LLMServiceFactory.defaultModel(provider.getValue())); compatible.load(provider.getValue(), Map.of()); });
        Button apiDefaults = new Button("Use API defaults"); apiDefaults.setId("regional-api-defaults");
        apiDefaults.setOnAction(e -> {
            var selected = LLMServiceFactory.defaults();
            if (LLMServiceFactory.isOffline(selected.provider())) return;
            provider.setValue(selected.provider()); model.setText(selected.model()); compatible.load(selected.provider(), selected.options());
            defaultTemperature.setSelected(selected.providerTemperature()); temperature.getValueFactory().setValue(selected.temperature());
            maxTokens.getValueFactory().setValue(selected.maxTokens());
        });
        VBox online = new VBox(6, new FlowPane(8, 6, provider, model, apiDefaults),
                new FlowPane(8, 6, defaultTemperature, temperature, new Label("Maximum output tokens"), maxTokens), compatible);
        online.setDisable(true);
        selection.setOnAction(e -> online.setDisable(!Set.of("llm", "google").contains(selection.getValue())));
        Button run = new Button("Run regional coordination"); run.setId("regional-run");
        Label status = new Label("Ready. Re-running replaces only the previous demo agents and resets the region setup."); status.setWrapText(true); status.setId("regional-status");
        run.setOnAction(e -> {
            precedingRequests = facade.runtime().requests().stream().map(AgentRequest::id).collect(java.util.stream.Collectors.toSet());
            boolean live = Set.of("llm", "google").contains(selection.getValue());
            try {
                if (live) { compatible.validateSettings(); maxTokens.commitValue(); if (model.getText().isBlank()) throw new IllegalArgumentException("Enter a model ID"); }
            } catch (RuntimeException ex) { status.setText("Invalid settings: " + ex.getMessage()); return; }
            var config = new LLMConfig(live ? provider.getValue() : "offline", live ? model.getText().strip() : "offline-rules",
                    temperature.getValue(), maxTokens.getValue(), live ? compatible.options(Map.of(LLMConfig.TEMPERATURE_MODE,
                    defaultTemperature.isSelected() ? "provider-default" : "explicit")) : Map.of());
            run.setDisable(true); status.setText("Running real agent work; " + (live ? "ONLINE " + config.provider() + "/" + config.model() + " selected, no fallback" : "classical policies and algorithmic planner; no LLM"));
            facade.runRegionalDemo(food.getValue(), energy.getValue(), transport.getValue(), selection.getValue(), config).whenComplete((v, error) -> Platform.runLater(() -> {
                run.setDisable(false); refresh();
                boolean failed = facade.runtime().requests().stream().filter(r -> !precedingRequests.contains(r.id())).anyMatch(r -> r.status() != AgentRequest.Status.SUCCEEDED);
                status.setText(error != null ? "Failed: " + (error.getCause() == null ? error : error.getCause()).getMessage()
                        : failed ? "Peer work failed or remains pending; inspect the result. No failed advice authorizes transfer." : "Round completed. Read the selected advice and actual before/after values below.");
            }));
        });
        addColumn("Region", e -> e.name());
        for (String resource : List.of("food", "energy")) {
            addColumn(resource + " initial → now", e -> String.valueOf(initial.getOrDefault(e.name(), Map.of()).getOrDefault(resource, "—")) + " → " + e.attributes().get(resource));
            addColumn(resource + " need", e -> String.valueOf(e.attributes().get(resource + "Need")));
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(160); table.setId("regional-table");
        outcomes.setEditable(false); outcomes.setWrapText(true); outcomes.setId("regional-outcomes"); state.setWrapText(true);
        Label help = new Label("Alder supplies food; Birch supplies energy; Cedar can have both shortages. Transfers conserve resources, protect donor needs and consume a shared transport budget. The planner computes feasible alternatives; the selector advises food, energy or no action; the monitor applies only a validated offered choice. Try transport=0 for infeasibility, or choose non-intervention. The selected online provider can choose any offered alternative or NONE. API credentials are managed in APIs; no offline fallback."); help.setWrapText(true);
        getChildren().addAll(help, new FlowPane(8, 6, new Label("Cedar food shortage"), food, new Label("Energy shortage"), energy, new Label("Transport units"), transport),
                new FlowPane(8, 6, selection, run), online, status, state, table, outcomes);
        VBox.setVgrow(outcomes, Priority.ALWAYS);
        facade.onEvent(e -> {
            if (e.type() == ExecutionEventType.ENVIRONMENT_ACTION && e.source().equals("scenario-setup")
                    && e.metadata().get("after") instanceof app.core.environment.WorldStateSnapshot snapshot && !snapshot.entities().isEmpty()) {
                initial = snapshot.entities().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(WorldEntity::name, WorldEntity::attributes));
            }
            if (queued.compareAndSet(false, true)) Platform.runLater(() -> { queued.set(false); refresh(); });
        });
        if (!facade.supportsResourceDemo()) { run.setDisable(true); status.setText("Current scenario has no regional setup"); }
        refresh();
    }
    private void addColumn(String label, java.util.function.Function<WorldEntity, String> value) {
        TableColumn<WorldEntity, String> column = new TableColumn<>(label); column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue()))); table.getColumns().add(column);
    }
    private void refresh() {
        var snapshot = facade.runtime().environment().snapshot(); table.getItems().setAll(snapshot.entities());
        state.setText("Transport remaining: " + snapshot.facts().getOrDefault("transportRemaining", "not configured") + " | Food shortage: " + snapshot.facts().getOrDefault("foodShortage", "—") + " | Energy shortage: " + snapshot.facts().getOrDefault("energyShortage", "—"));
        outcomes.setText(facade.runtime().requests().stream().filter(r -> !precedingRequests.contains(r.id())).map(r ->
                facade.runtime().find(r.requesterId()).map(a -> a.name()).orElse("Previous agent") + " → " + facade.runtime().find(r.recipientId()).map(a -> a.name()).orElse("Previous agent")
                        + " | " + r.outcomeLabel() + "\nRequest: " + r.id() + "\n" + r.result() + "\nOutputs: " + r.outputs()
                        + "\n" + facade.runtime().find(r.recipientId()).map(a -> a.llmStatus().toString()).orElse(""))
                .collect(java.util.stream.Collectors.joining("\n\n"))
                + "\n\nLatest agent outcomes:\n" + facade.runtime().agents().stream().filter(a -> a.name().startsWith("Regional "))
                    .map(a -> a.name() + " | " + a.status() + ": " + a.lastOutcome()).collect(java.util.stream.Collectors.joining("\n")));
    }
}
