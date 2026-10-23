package com.erimali.multiAgentFX;

import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import com.erimali.multiAgentFX.panel.AgentSidebarPane;
import com.erimali.multiAgentFX.panel.ConsolePane;
import com.erimali.multiAgentFX.panel.EnvironmentPane;
import com.erimali.multiAgentFX.panel.OrganizationPane;
import com.erimali.multiAgentFX.panel.SimulationToolbarPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

public class MainLayoutBuilder {
    private final RuntimeFacade runtimeFacade;
    private ConsolePane consolePane;
    private AgentSidebarPane agentSidebarPane;
    private SimulationToolbarPane toolbarPane;

    public MainLayoutBuilder(RuntimeFacade runtimeFacade) {
        this.runtimeFacade = runtimeFacade;
    }

    public BorderPane build() {
        BorderPane root = new BorderPane();


        consolePane = new ConsolePane();
        agentSidebarPane = new AgentSidebarPane(runtimeFacade.agents());
        toolbarPane = new SimulationToolbarPane();

        TabPane sideTabs = new TabPane(
                tab("Agents", agentSidebarPane),
                tab("Environments", new EnvironmentPane(runtimeFacade)),
                tab("Organizations", new OrganizationPane(runtimeFacade))
        );
        sideTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sideTabs.setPrefWidth(320);

        TabPane scenarioTabs = new TabPane(tab("Regional coordination", new com.erimali.multiAgentFX.panel.RegionalDemoPane(runtimeFacade)),
                tab("Scalar regression demo", new com.erimali.multiAgentFX.panel.ResourceDemoPane(runtimeFacade)));
        scenarioTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        TabPane activity = new TabPane(tab("Tasks / Requests", new com.erimali.multiAgentFX.panel.AgentWorkPane(runtimeFacade)),
                tab("LLM Peer Review / Activity", new com.erimali.multiAgentFX.panel.ActivityPane(runtimeFacade)),
                tab("Conversations", new com.erimali.multiAgentFX.panel.ConversationPane(runtimeFacade)),
                tab("Hybrid Demo", scenarioTabs), tab("Console", consolePane));
        activity.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        javafx.scene.control.SplitPane workspace = new javafx.scene.control.SplitPane(activity, sideTabs);
        workspace.setDividerPositions(.74);
        root.setCenter(workspace);
        root.setBottom(toolbarPane);
        return root;
    }

    public ConsolePane consolePane() {
        return consolePane;
    }

    public AgentSidebarPane agentSidebarPane() {
        return agentSidebarPane;
    }

    public SimulationToolbarPane toolbarPane() {
        return toolbarPane;
    }


    private Tab tab(String title, javafx.scene.Node content) {
        return new Tab(title, content);
    }
}
