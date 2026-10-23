package com.erimali.multiAgentFX;

import com.erimali.multiAgentFX.bridge.LLMTestFacade;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import com.erimali.multiAgentFX.model.AgentConfig;
import com.erimali.multiAgentFX.panel.AgentSidebarPane;
import com.erimali.multiAgentFX.panel.ConsolePane;
import com.erimali.multiAgentFX.panel.SimulationToolbarPane;
import com.erimali.multiAgentFX.stage.AgentEditorStage;
import com.erimali.multiAgentFX.stage.LLMTestStage;
import com.erimali.multiAgentFX.stage.ManageAPIStage;
import com.erimali.multiAgentFX.stage.RuntimeMonitorStage;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.application.Platform;

public class MainController {
    private final Stage owner;
    private final RuntimeFacade runtimeFacade;
    private final LLMTestFacade llmTestFacade;
    private ConsolePane consolePane;
    private AgentConfig selectedAgent;

    public MainController(Stage owner, RuntimeFacade runtimeFacade, LLMTestFacade llmTestFacade) {
        this.owner = owner;
        this.runtimeFacade = runtimeFacade;
        this.llmTestFacade = llmTestFacade;
    }

    public void wire(ConsolePane consolePane, AgentSidebarPane agentSidebar, SimulationToolbarPane toolbar) {
        this.consolePane = consolePane;
        consolePane.setOnCommand(this::handleConsoleCommand);
        agentSidebar.setOnCreate(this::createAgent);
        agentSidebar.setOnEdit(this::editAgent);
        agentSidebar.setOnDelete(this::deleteAgent);
        agentSidebar.setOnSelection(agent -> selectedAgent = agent);
        toolbar.setOnStep(this::stepRuntime);
        toolbar.setOnStart(() -> runtimeFacade.runtime().start());
        toolbar.setOnStop(() -> runtimeFacade.runtime().stop());
        toolbar.setOnRuntimeMonitor(this::openRuntimeMonitor);
        toolbar.setOnLLMTest(this::openLLMTest);
        toolbar.setOnManageApis(this::openApiManager);
        toolbar.setOnLoadDemo(this::loadDemo);
        runtimeFacade.onEvent(event -> Platform.runLater(() -> log(formatEvent(event))));
    }

    public void initialize() {
        log("System initialized. Select or create an agent, then enter a task.");
        log(runtimeFacade.runtimeSummary());
    }

    private void handleConsoleCommand(String command) {
        if (selectedAgent == null) {
            log("[UI] Select an agent before submitting a task.");
            return;
        }
        log("[USER -> " + selectedAgent.getName() + "] " + command);
        runtimeFacade.submitTask(selectedAgent.getId(), command);
    }

    private void createAgent() {
        AgentEditorStage editor = new AgentEditorStage(owner, runtimeFacade.newDraft(), true, runtimeFacade);
        editor.showAndWait();
        if (editor.isSaved()) {

            log("Created agent: " + editor.getResult());
        }
    }

    private void editAgent(AgentConfig selected) {
        AgentEditorStage editor = new AgentEditorStage(owner, selected, false, runtimeFacade);
        editor.showAndWait();
        if (editor.isSaved()) {

            log("Updated agent: " + editor.getResult());
        }
    }

    private void deleteAgent(AgentConfig selected) {
        runtimeFacade.deleteAgent(selected);
        log("Deleted agent: " + selected);
    }

    private void stepRuntime() {
        runtimeFacade.step();
        log("[Runtime] " + runtimeFacade.runtimeSummary());
    }

    private void openRuntimeMonitor() {
        new RuntimeMonitorStage(owner, runtimeFacade).show();
    }

    private void openLLMTest() {
        new LLMTestStage(owner, llmTestFacade).show();
    }

    private void openApiManager() {
        new ManageAPIStage(owner).show();
    }

    private void loadDemo() {
        runtimeFacade.loadDemoScenario();
        log("[Demo] Regional coordination: monitor → feasible-options planner → selector → validated transfer. APIs defaults select real Google or a classical food-priority selector. Configure runs in Hybrid Demo.");
    }

    private String formatEvent(app.core.runtime.ExecutionEvent event) {
        String agentName = event.agentId() == null ? event.source() : runtimeFacade.findAgent(event.agentId())
                .map(AgentConfig::getName).orElse(event.source());
        return "[" + event.type() + "][" + agentName + "] " + event.message();
    }

    private void log(String message) {
        if (consolePane == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
            alert.initOwner(owner);
            alert.showAndWait();
            return;
        }
        consolePane.appendLog(message);
    }
}
