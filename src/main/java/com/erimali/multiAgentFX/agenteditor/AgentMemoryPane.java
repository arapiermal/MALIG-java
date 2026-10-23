package com.erimali.multiAgentFX.agenteditor;
import app.core.memory.*;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import com.erimali.multiAgentFX.model.AgentConfig;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

public final class AgentMemoryPane extends VBox {
    private final CheckBox shortMemory = new CheckBox("Bounded short-term context");
    private final CheckBox episodes = new CheckBox("Episodic retrieval (in-memory, up to 1000 entries or context capacity)");
    private final Spinner<Integer> capacity = new Spinner<>(1, 100000, 50);
    private final TextArea entries = new TextArea();
    public AgentMemoryPane(RuntimeFacade facade, UUID id) {
        setSpacing(10); setPadding(new javafx.geometry.Insets(10));
        entries.setEditable(false); entries.setWrapText(true);
        TextField query = new TextField(); query.setPromptText("Search memory, or enter a note");
        Runnable refresh = () -> entries.setText(facade.findRuntimeAgent(id).map(a ->
                "Structured state: " + a.memory().state() + "\n\nRecent context:\n" + a.memory().recallRecent(20)
                        + "\n\nEpisodes:\n" + (a.memory() instanceof ModularMemory m ? m.episodes(20) : List.of()))
                .orElse("Save the agent to initialize memory."));
        Button read = new Button("Refresh");
        Button search = new Button("Retrieve");
        Button note = new Button("Store Note");
        Button clear = new Button("Clear Runtime Memory");
        read.setOnAction(e -> refresh.run());
        search.setOnAction(e -> facade.findRuntimeAgent(id).ifPresent(a -> entries.setText(a.memory().search(query.getText(), 10).toString())));
        note.setOnAction(e -> {
            String text = query.getText();
            if (!text.isBlank()) facade.runAsync(() -> facade.findRuntimeAgent(id).ifPresent(a ->
                    a.memory().remember(new MemoryEntry(null, "document", "human", text, null, Map.of()))))
                    .thenRun(() -> javafx.application.Platform.runLater(refresh));
        });
        clear.setOnAction(e -> facade.runAsync(() -> facade.findRuntimeAgent(id).ifPresent(a -> a.memory().clear()))
                .thenRun(() -> javafx.application.Platform.runLater(refresh)));
        getChildren().addAll(shortMemory, episodes, new HBox(8, new Label("Context capacity:"), capacity),
                new Label("Settings apply on Save. Runtime note/clear operations apply immediately."), query,
                new HBox(8, read, search, note, clear), entries);
        VBox.setVgrow(entries, Priority.ALWAYS);
        refresh.run();
    }
    public void loadFrom(AgentConfig c) { shortMemory.setSelected(c.isShortMemoryEnabled()); episodes.setSelected(c.isLongMemoryEnabled()); capacity.getValueFactory().setValue(c.getMemoryLimit()); }
    public void writeTo(AgentConfig c) { c.setShortMemoryEnabled(shortMemory.isSelected()); c.setLongMemoryEnabled(episodes.isSelected()); c.setMemoryLimit(capacity.getValue()); }
}
