package com.erimali.multiAgentFX.stage;

import com.erimali.multiAgentFX.agenteditor.*;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import com.erimali.multiAgentFX.model.AgentConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.util.*;

public final class AgentEditorStage extends Stage {
    private final AgentConfig workingCopy;
    private final AgentGeneralPane general = new AgentGeneralPane();
    private final AgentBehaviorPane behavior;
    private final AgentToolsPane tools;
    private final AgentMemoryPane memory;
    private boolean saved;
    public AgentEditorStage(Stage owner, AgentConfig source, boolean creating, RuntimeFacade facade) {
        workingCopy = source.copy();
        facade.findRuntimeAgent(source.getId()).ifPresent(a -> workingCopy.setEnabled(a.status() != app.core.agent.AgentStatus.STOPPED));
        behavior = new AgentBehaviorPane(facade.factory());
        tools = new AgentToolsPane(facade.factory().toolNames());
        memory = new AgentMemoryPane(facade, source.getId());
        AgentRuntimePane runtime = new AgentRuntimePane(facade, source.getId());
        setOnHidden(event -> runtime.close());
        initOwner(owner); initModality(Modality.APPLICATION_MODAL);
        setTitle(creating ? "Create Agent" : "Edit Agent");
        general.loadFrom(workingCopy); behavior.loadFrom(workingCopy); tools.loadFrom(workingCopy); memory.loadFrom(workingCopy);
        general.typeProperty().addListener((observable, oldType, newType) -> { if (newType != null) behavior.setType(newType); });
        ScrollPane behaviorScroll = new ScrollPane(behavior); behaviorScroll.setFitToWidth(true);
        TabPane tabs = new TabPane(new Tab("General", general), new Tab("Behavior / Planner", behaviorScroll),
                new Tab("Tools", tools), new Tab("Memory / State", memory), new Tab("Runtime / History", runtime));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Button save = new Button("Save"), cancel = new Button("Cancel");
        Label feedback = new Label();
        save.setOnAction(e -> {
            List<String> errors = new ArrayList<>(general.validateInput()); errors.addAll(behavior.validateInput());
            if (!errors.isEmpty()) { feedback.setText(String.join("\n", errors)); return; }
            general.writeTo(workingCopy); behavior.writeTo(workingCopy); tools.writeTo(workingCopy); memory.writeTo(workingCopy);
            save.setDisable(true); cancel.setDisable(true);
            facade.saveAgent(workingCopy).whenComplete((ignored, error) -> Platform.runLater(() -> {
                if (error == null) { saved = true; close(); }
                else {
                    feedback.setText("Save failed: " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage()));
                    save.setDisable(false); cancel.setDisable(false);
                }
            }));
        });
        cancel.setOnAction(e -> close());
        BorderPane root = new BorderPane(tabs);
        root.setPadding(new Insets(12));
        root.setBottom(new HBox(10, feedback, save, cancel));
        setScene(new Scene(root, 1050, 740));
    }
    public boolean isSaved() { return saved; }
    public AgentConfig getResult() { return workingCopy; }
}
