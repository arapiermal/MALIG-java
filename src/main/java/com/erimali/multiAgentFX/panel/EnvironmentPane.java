package com.erimali.multiAgentFX.panel;

import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class EnvironmentPane extends VBox {
    public EnvironmentPane(RuntimeFacade facade) {
        setSpacing(8); setPadding(new javafx.geometry.Insets(6));
        ListView<String> facts = new ListView<>();
        Label summary = new Label(facade.scenarioName());
        Button inspect = new Button("Inspect");
        Runnable refresh = () -> {
            var snapshot = facade.runtime().environment().snapshot();
            facts.getItems().setAll(snapshot.facts().entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).toList());
            snapshot.entities().forEach(entity -> facts.getItems().add(entity.name() + ": " + entity.attributes()));
        };
        inspect.setOnAction(e -> refresh.run());
        facade.onEvent(e -> { if (e.type() == app.core.runtime.ExecutionEventType.ACTION_COMPLETED
                || e.type() == app.core.runtime.ExecutionEventType.ENVIRONMENT_TICK) Platform.runLater(refresh); });
        getChildren().addAll(summary, facts, inspect);
        VBox.setVgrow(facts, Priority.ALWAYS);
        refresh.run();
    }
}
