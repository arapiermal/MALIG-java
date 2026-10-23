package com.erimali.multiAgentFX.panel;

import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;

public class SimulationToolbarPane extends ToolBar {
    private final Button startButton = new Button("Run");
    private final Button stopButton = new Button("Stop");
    public void setOnStart(Runnable action) { startButton.setOnAction(e -> action.run()); }
    public void setOnStop(Runnable action) { stopButton.setOnAction(e -> action.run()); }
    private final Button stepButton = new Button("Step");
    private final Button runtimeButton = new Button("Runtime");
    private final Button llmTestButton = new Button("LLM Test");
    private final Button apiButton = new Button("APIs");
    private final Button demoButton = new Button("Load Demo");

    public SimulationToolbarPane() {
        getItems().addAll(startButton, stopButton, stepButton, demoButton, new Separator(), runtimeButton, llmTestButton, apiButton);
    }

    public void setOnStep(Runnable action) {
        stepButton.setOnAction(e -> action.run());
    }

    public void setOnRuntimeMonitor(Runnable action) {
        runtimeButton.setOnAction(e -> action.run());
    }

    public void setOnLLMTest(Runnable action) {
        llmTestButton.setOnAction(e -> action.run());
    }

    public void setOnManageApis(Runnable action) {
        apiButton.setOnAction(e -> action.run());
    }

    public void setOnLoadDemo(Runnable action) {
        demoButton.setOnAction(e -> action.run());
    }
}
