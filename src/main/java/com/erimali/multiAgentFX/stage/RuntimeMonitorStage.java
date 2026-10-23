package com.erimali.multiAgentFX.stage;

import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

public class RuntimeMonitorStage extends Stage {
    public RuntimeMonitorStage(Stage owner, RuntimeFacade runtimeFacade) {
        initOwner(owner);
        setTitle("Runtime Monitor");
        Label summary = new Label();
        TextArea agents = new TextArea();
        agents.setEditable(false);
        TextArea events = new TextArea();
        events.setEditable(false);
        events.setWrapText(true);
        VBox root = new VBox(8, summary, new Label("Agents"), agents, new Label("Recent events"), events);
        root.setPadding(new Insets(12));
        setScene(new Scene(root, 720, 520));

        Runnable refresh = () -> {
            summary.setText(runtimeFacade.runtimeSummary());
            agents.setText(runtimeFacade.runtime().agents().stream()
                    .map(agent -> agent.name() + "\t" + agent.getClass().getSimpleName() + "\t" + agent.status()
                            + (agent.lastInvocationFailed() ? " | last task FAILED" : "") + "\n  " + agent.llmStatus() + "\n  " + agent.lastOutcome())
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
            events.setText(runtimeFacade.recentEvents(30).stream()
                    .map(event -> event.type() + " | " + event.source() + " | " + event.message() + " " + event.metadata())
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
        };
        refresh.run();
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh.run()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        setOnHidden(event -> timeline.stop());
    }
}
