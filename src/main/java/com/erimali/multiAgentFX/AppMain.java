package com.erimali.multiAgentFX;

import com.erimali.multiAgentFX.bridge.LLMTestFacade;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class AppMain extends Application {
    @Override
    public void start(Stage stage) {
        RuntimeFacade runtimeFacade = new RuntimeFacade(new app.scenario.synthetic.ResourceScenario());
        LLMTestFacade llmTestFacade = new LLMTestFacade();
        MainLayoutBuilder layoutBuilder = new MainLayoutBuilder(runtimeFacade);
        BorderPane root = layoutBuilder.build();

        MainController controller = new MainController(stage, runtimeFacade, llmTestFacade);
        controller.wire(layoutBuilder.consolePane(), layoutBuilder.agentSidebarPane(), layoutBuilder.toolbarPane());

        stage.setTitle("Hybrid Multi-Agent System MALIG");
        stage.setScene(new Scene(root, 1200, 800));
        stage.setOnCloseRequest(event -> runtimeFacade.close());
        stage.show();
        controller.initialize();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
