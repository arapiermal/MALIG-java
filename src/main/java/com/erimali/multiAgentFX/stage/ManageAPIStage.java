package com.erimali.multiAgentFX.stage;

import app.llm.provider.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;

public final class ManageAPIStage extends Stage {
    public ManageAPIStage(Stage owner) {
        if (owner != null) initOwner(owner); setTitle("Provider and API Credentials");
        CredentialStore credentials = CredentialStore.current();
        ComboBox<String> provider = new ComboBox<>(); provider.getItems().setAll(LLMServiceFactory.providers());
        provider.setConverter(new StringConverter<>() {
            public String toString(String value) { return value == null ? "" : LLMServiceFactory.label(value); }
            public String fromString(String text) { return text; }
        });
        var defaults = LLMServiceFactory.defaults();
        provider.setValue(LLMServiceFactory.isOffline(defaults.provider()) ? "offline" : defaults.provider());
        CompatibleSettingsPane compatible = new CompatibleSettingsPane(); compatible.load(defaults.provider(), defaults.options());
        TextField model = new TextField(defaults.model());
        model.setPromptText("Any model ID supported by your account");
        CheckBox providerTemperature = new CheckBox("Use provider default temperature (recommended for new models)");
        providerTemperature.setSelected(defaults.providerTemperature());
        model.setDisable(LLMServiceFactory.isOffline(provider.getValue()));
        PasswordField key = new PasswordField(); key.setPromptText("Enter a new key (existing secrets are never displayed)");
        Label status = new Label(), feedback = new Label(); status.setWrapText(true); feedback.setWrapText(true);
        Runnable refresh = () -> {
            try { status.setText(LLMServiceFactory.isOffline(provider.getValue()) ? "Offline test provider: no credentials used" : credentials.status(provider.getValue())); } catch (RuntimeException ex) { status.setText(ex.getMessage()); }
        };
        Button reload = new Button("Reload / Detect"), session = new Button("Use Session Key"),
                clearSession = new Button("Clear Session Override"), saveLocal = new Button("Write Key to .env"),
                removeLocal = new Button("Remove Selected Local Keys"), apply = new Button("Apply Provider Defaults");
        reload.setOnAction(e -> refresh.run());
        session.setOnAction(e -> {
            try { credentials.setSessionKey(provider.getValue(), key.getText()); key.clear(); refresh.run(); }
            catch (RuntimeException ex) { feedback.setText(ex.getMessage()); }
        });
        clearSession.setOnAction(e -> { credentials.setSessionKey(provider.getValue(), null); refresh.run(); });
        saveLocal.setOnAction(e -> {
            try {
                if (key.getText().isBlank()) throw new IllegalArgumentException("Enter the key to write.");
                credentials.writeLocalKey(provider.getValue(), key.getText()); key.clear(); refresh.run();
                feedback.setText("Local key updated. Higher-priority session/process keys still take precedence.");
            } catch (RuntimeException ex) { feedback.setText(ex.getMessage()); }
        });
        removeLocal.setOnAction(e -> {
            try { credentials.writeLocalKey(provider.getValue(), null); refresh.run(); feedback.setText("Selected provider keys removed; unrelated entries preserved."); }
            catch (RuntimeException ex) { feedback.setText(ex.getMessage()); }
        });
        apply.setOnAction(e -> {
            if (!LLMServiceFactory.isOffline(provider.getValue()) && (model.getText().isBlank() || model.getText().equals("offline-rules"))) {
                feedback.setText("Enter a model ID."); return;
            }
            try { compatible.validateSettings(); } catch (RuntimeException ex) { feedback.setText(ex.getMessage()); return; }
            compatible.options(java.util.Map.of()).forEach((k,v) -> System.setProperty("hmas.llm." + provider.getValue() + "." + k, v));
            System.setProperty(LLMServiceFactory.PROVIDER_PROPERTY, provider.getValue());
            System.setProperty(LLMServiceFactory.MODEL_PROPERTY, !LLMServiceFactory.isOffline(provider.getValue()) ? model.getText().strip() : "offline-rules");
            System.setProperty(LLMServiceFactory.TEMPERATURE_MODE_PROPERTY, providerTemperature.isSelected() ? "provider-default" : "explicit");
            feedback.setText("Defaults applied to new configurations, demos and LLM tests. Edit existing agents to change provider/model.");
        });
        provider.setOnAction(e -> {
            model.setDisable(LLMServiceFactory.isOffline(provider.getValue()));
            model.setText(LLMServiceFactory.defaultModel(provider.getValue())); compatible.load(provider.getValue(), java.util.Map.of());
            key.clear(); refresh.run();
            boolean offline = LLMServiceFactory.isOffline(provider.getValue());
            for (Button button : java.util.List.of(session, clearSession, saveLocal, removeLocal)) button.setDisable(offline);
        });
        boolean offline = LLMServiceFactory.isOffline(provider.getValue());
        for (Button button : java.util.List.of(session, clearSession, saveLocal, removeLocal)) button.setDisable(offline);
        VBox root = new VBox(10, new Label("Provider"), provider, new Label("Model ID — free text, no whitelist"), model, providerTemperature, compatible, apply,
                new Separator(), new Label("Credential precedence: session > process environment > project .env"),
                new Label("Local file: " + credentials.envFile()), status, reload, key,
                new HBox(8, session, clearSession), new HBox(8, saveLocal, removeLocal), feedback,
                new Label(".env is a plaintext development file ignored by Git. No permanent OS environment changes are made."));
        root.setPadding(new javafx.geometry.Insets(14)); setScene(new Scene(new ScrollPane(root), 800, 780)); refresh.run();
    }
}
