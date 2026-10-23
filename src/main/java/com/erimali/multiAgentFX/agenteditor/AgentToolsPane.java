package com.erimali.multiAgentFX.agenteditor;
import com.erimali.multiAgentFX.model.AgentConfig;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.*;
import java.util.stream.Collectors;

public final class AgentToolsPane extends VBox {
    private final Map<String, CheckBox> tools = new LinkedHashMap<>();
    public AgentToolsPane(Set<String> names) {
        setSpacing(10); setPadding(new javafx.geometry.Insets(10));
        getChildren().add(new Label("Granted tools — disabled tools cannot execute through plans or LLM calls."));
        names.forEach(name -> { CheckBox box = new CheckBox(name); tools.put(name, box); getChildren().add(box); });
    }
    public void loadFrom(AgentConfig c) { tools.forEach((name, box) -> box.setSelected(c.getTools().contains(name))); }
    public void writeTo(AgentConfig c) {
        c.setTools(tools.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey).collect(Collectors.toSet()));
    }
}
