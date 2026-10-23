package com.erimali.multiAgentFX.stage;

import app.llm.*;
import app.llm.provider.*;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.*;

/** Same explicit adapter settings in APIs, agent editor and provider test. Never contains secrets. */
public final class CompatibleSettingsPane extends VBox {
    private final TextField base = new TextField();
    private final ComboBox<String> tools = new ComboBox<>(), tokens = new ComboBox<>(), role = new ComboBox<>();
    private String provider = "offline";
    public CompatibleSettingsPane() {
        super(5); base.setId("compatible-base-url");
        tools.getItems().setAll("enabled", "disabled"); tokens.getItems().setAll("max_tokens", "max_completion_tokens");
        role.getItems().setAll("system", "developer");
        getChildren().addAll(new Label("HTTPS API base URL (credentials go only to this endpoint; no redirects)"), base,
                new Label("Model function support — disabled rejects tool-enabled tasks"), tools,
                new Label("Output limit parameter"), tokens, new Label("Instruction role"), role);
        load("offline", Map.of());
    }
    public void load(String provider, Map<String, String> options) {
        this.provider = provider;
        boolean compatible = CompatibleProfile.find(provider).isPresent(); setVisible(compatible); setManaged(compatible);
        var values = new HashMap<>(LLMServiceFactory.defaultOptions(provider)); values.putAll(options);
        base.setText(values.getOrDefault("baseUrl", "")); tools.setValue(values.getOrDefault("toolMode", "enabled"));
        tokens.setValue(values.getOrDefault("tokenParameter", "max_tokens")); role.setValue(values.getOrDefault("instructionRole", "system"));
    }
    public Map<String, String> options(Map<String, String> existing) {
        var values = new HashMap<>(existing);
        for (String key : List.of("baseUrl", "toolMode", "tokenParameter", "instructionRole")) values.remove(key);
        if (CompatibleProfile.find(provider).isPresent()) {
            values.put("baseUrl", base.getText().strip()); values.put("toolMode", tools.getValue());
            values.put("tokenParameter", tokens.getValue()); values.put("instructionRole", role.getValue());
        }
        return Map.copyOf(values);
    }
    public void validateSettings() {
        if (CompatibleProfile.find(provider).isPresent()) CompatibleProfile.endpoint(new LLMConfig(provider, "validation", 0, 1, options(Map.of())));
    }
}
