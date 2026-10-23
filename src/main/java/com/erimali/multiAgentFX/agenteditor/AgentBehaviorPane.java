package com.erimali.multiAgentFX.agenteditor;

import app.core.agent.*;
import app.llm.provider.LLMServiceFactory;
import com.erimali.multiAgentFX.model.AgentConfig;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.util.*;

public final class AgentBehaviorPane extends VBox {
    private final AgentFactory factory;
    private AgentType type = AgentType.BASIC;
    private final ComboBox<String> policy = new ComboBox<>(), planner = new ComboBox<>(), provider = new ComboBox<>();
    private final com.erimali.multiAgentFX.stage.CompatibleSettingsPane compatible = new com.erimali.multiAgentFX.stage.CompatibleSettingsPane();
    private final TextField model = new TextField();
    private final Spinner<Double> temperature = new Spinner<>(0.0, 2.0, 0.2, 0.1);
    private final CheckBox providerTemperature = new CheckBox("Use provider default temperature (omit parameter)");
    private final Spinner<Integer> maxTokens = new Spinner<>(1, Integer.MAX_VALUE, 4096, 256);
    private final TextArea system = new TextArea(), instructions = new TextArea();
    private final VBox policyGroup = new VBox(8), plannerGroup = new VBox(8), providerGroup = new VBox(8), onlineGroup = new VBox(8);
    private final GridPane optionsForm = FormLayout.createFormGrid();
    private final Map<String, TextField> options = new LinkedHashMap<>();
    private final Label explanation = new Label(), policyDescription = new Label(), providerNote = new Label();
    public AgentBehaviorPane(AgentFactory factory) {
        this.factory = factory;
        model.setId("behavior-model"); model.setPromptText("Any model ID supported by your provider/account");
        providerTemperature.setId("behavior-default-temperature");
        temperature.disableProperty().bind(providerTemperature.selectedProperty());
        maxTokens.setEditable(true);
        setSpacing(12); setPadding(new javafx.geometry.Insets(10));
        policy.getItems().setAll(factory.policies().definitions().stream().map(PolicyRegistry.Definition::id).toList());
        planner.getItems().setAll(factory.plannerNames()); provider.getItems().setAll(LLMServiceFactory.providers());
        provider.setConverter(new StringConverter<>() {
            public String toString(String value) { return value == null ? "" : LLMServiceFactory.label(value); }
            public String fromString(String text) { return text; }
        });
        system.setPrefRowCount(3); instructions.setPrefRowCount(3);
        policy.setOnAction(e -> buildOptions(Map.of()));
        policy.setId("behavior-policy");
        planner.setOnAction(e -> updateVisibility());
        provider.setOnAction(e -> {
            if (!LLMServiceFactory.isOffline(provider.getValue())) model.setText(LLMServiceFactory.defaultModel(provider.getValue()));
            compatible.load(provider.getValue(), Map.of());
            updateVisibility();
        });
        policyGroup.getChildren().addAll(new Label("Deterministic policy"), policy, policyDescription, optionsForm);
        plannerGroup.getChildren().addAll(new Label("Planner strategy"), planner);
        GridPane onlineForm = FormLayout.createFormGrid();
        FormLayout.addRow(onlineForm, 0, "Model ID (free text)", model); FormLayout.addRow(onlineForm, 1, "Temperature", new VBox(4, providerTemperature, temperature));
        FormLayout.addRow(onlineForm, 2, "Maximum output tokens", maxTokens);
        FormLayout.addRow(onlineForm, 3, "System prompt", system); FormLayout.addRow(onlineForm, 4, "Instructions", instructions);
        onlineGroup.getChildren().addAll(onlineForm, compatible);
        providerGroup.getChildren().addAll(new Label("Reasoning provider"), provider, providerNote, onlineGroup);
        explanation.setWrapText(true); policyDescription.setWrapText(true); providerNote.setWrapText(true);
        getChildren().addAll(explanation, policyGroup, plannerGroup, providerGroup);
        policy.setValue("rules"); planner.setValue("auto"); provider.setValue("offline");
        updateVisibility();
    }
    private void buildOptions(Map<String, String> values) {
        options.clear(); optionsForm.getChildren().clear();
        factory.policies().definitions().stream().filter(d -> d.id().equals(policy.getValue())).findFirst().ifPresent(d -> {
            policyDescription.setText(d.description());
            new TreeMap<>(d.defaults()).forEach((key, initial) -> {
                TextField field = new TextField(values.getOrDefault(key, initial));
                javafx.scene.Node input = field;
                if (key.equals("peer") || key.endsWith("Peer")) {
                    ComboBox<String> peers = new ComboBox<>(); peers.setEditable(true);
                    peers.getItems().setAll(factory.runtime().agents().stream().map(AbstractAgent::name).distinct().toList());
                    String selected = field.getText(); peers.setValue(selected); field = peers.getEditor(); field.setText(selected); input = peers;
                    peers.setPromptText("Exact unique peer name or UUID");
                } else if (Set.of("threshold-actions", "guarded-peer").contains(policy.getValue())) {
                    List<String> choices = switch (key) {
                        case "source" -> List.of("OBSERVATION", "STATE");
                        case "comparison" -> List.of("LT", "LE", "EQ", "GE", "GT");
                        case "valueType" -> List.of("BOOLEAN", "INTEGER", "NUMBER", "STRING");
                        case "otherwise" -> List.of("NO_ACTION", "REJECT", "DEFER");
                        case "tool" -> { var names = new ArrayList<>(factory.toolNames()); names.addFirst(""); yield names; }
                        default -> List.of();
                    };
                    if (!choices.isEmpty()) {
                        ComboBox<String> choice = new ComboBox<>(); choice.setEditable(true); choice.getItems().setAll(choices);
                        String selected = field.getText(); choice.setValue(selected); field = choice.getEditor(); field.setText(selected); input = choice;
                    }
                }
                field.setId("policy-option-" + key);
                options.put(key, field); FormLayout.addRow(optionsForm, options.size() - 1, key, input);
            });
        });
    }
    public void setType(AgentType type) { this.type = type; updateVisibility(); }
    private boolean usesLlm() {
        return type == AgentType.LLM || type == AgentType.HYBRID ||
                type == AgentType.PLANNER && Set.of("auto", "llm").contains(planner.getValue() == null ? "" : planner.getValue());
    }
    private void show(javafx.scene.Node node, boolean value) { node.setVisible(value); node.setManaged(value); }
    private void updateVisibility() {
        show(policyGroup, type == AgentType.BASIC || type == AgentType.HYBRID);
        show(plannerGroup, type == AgentType.PLANNER); show(providerGroup, usesLlm());
        show(onlineGroup, !LLMServiceFactory.isOffline(provider.getValue()));
        providerNote.setText(!LLMServiceFactory.isOffline(provider.getValue())
                ? "Explicit agent settings, copied from API defaults when created. Any model ID is allowed; availability/features are checked by the provider. Sampling overrides may be unsupported. No offline fallback."
                : "Explicit offline test substitute. No language model or network request is used.");
        explanation.setText(switch (type) {
            case BASIC -> "A deterministic Java policy proposes actions; granted capabilities control execution.";
            case PLANNER -> "Select a planner. Auto may invoke the chosen reasoning provider for unsupported classical tasks.";
            case LLM -> "The provider reasons; HMAS validates and executes every proposed tool call.";
            case HYBRID -> "Optional composition: selected classical policy, then LLM only when that policy does not support the task.";
            case HUMAN -> "Use Runtime / History to submit typed tool proposals for queued work. No LLM configuration is used.";
        });
    }
    public void loadFrom(AgentConfig c) {
        type = c.getType(); planner.setValue(c.getPlanner()); policy.setValue(c.getPolicy()); buildOptions(c.getPolicyOptions());
        provider.setValue(LLMServiceFactory.isOffline(c.getProvider()) ? "offline" : c.getProvider()); model.setText(c.getModel()); compatible.load(c.getProvider(), c.getLlmOptions());
        maxTokens.getValueFactory().setValue(c.getMaxTokens());
        providerTemperature.setSelected(c.isProviderTemperature());
        temperature.getValueFactory().setValue(c.getTemperature()); system.setText(c.getSystemPrompt()); instructions.setText(c.getInstructions());
        updateVisibility();
    }
    public void writeTo(AgentConfig c) {
        if (type == AgentType.BASIC || type == AgentType.HYBRID) {
            c.setPolicy(policy.getValue()); c.setPolicyOptions(optionValues());
        }
        if (type == AgentType.PLANNER) c.setPlanner(planner.getValue());
        if (usesLlm()) {
            maxTokens.commitValue();
            c.setLlmOptions(compatible.options(c.getLlmOptions()));
            c.setProviderTemperature(providerTemperature.isSelected());
            c.setProvider(provider.getValue());
            c.setModel(!LLMServiceFactory.isOffline(provider.getValue()) ? model.getText().strip() : "offline-rules");
            c.setTemperature(!LLMServiceFactory.isOffline(provider.getValue()) ? temperature.getValue() : 0);
            if (!LLMServiceFactory.isOffline(provider.getValue())) c.setMaxTokens(maxTokens.getValue());
            c.setSystemPrompt(system.getText()); c.setInstructions(instructions.getText());
        }
    }
    private Map<String, String> optionValues() {
        Map<String, String> values = new LinkedHashMap<>(); options.forEach((key, field) -> values.put(key, field.getText().strip())); return values;
    }
    public List<String> validateInput() {
        List<String> errors = new ArrayList<>();
        if (type == AgentType.BASIC || type == AgentType.HYBRID) {
            try { factory.policies().resolve(policy.getValue(), optionValues()); } catch (RuntimeException ex) { errors.add(ex.getMessage()); }
        }
        if (type == AgentType.PLANNER && planner.getValue() == null) errors.add("Choose a planner.");
        if (usesLlm() && (provider.getValue() == null || !LLMServiceFactory.isOffline(provider.getValue()) && model.getText().isBlank()))
            errors.add("Choose a provider and a model ID.");
        if (usesLlm()) try { compatible.validateSettings(); } catch (RuntimeException ex) { errors.add(ex.getMessage()); }
        return errors;
    }
}
