package com.erimali.multiAgentFX.panel;

import com.erimali.multiAgentFX.model.AgentConfig;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class AgentSidebarPane extends VBox {
    private final ListView<AgentConfig> agentList = new ListView<>();
    private Consumer<AgentConfig> editHandler = agent -> { };
    private Runnable createHandler = () -> { };
    private Consumer<AgentConfig> deleteHandler = agent -> { };
    private Consumer<AgentConfig> selectionHandler = agent -> { };

    public AgentSidebarPane(ObservableList<AgentConfig> agents) {
        setSpacing(8);
        setPadding(new javafx.geometry.Insets(4));
        agentList.setItems(agents);

        Button createButton = new Button("Create");
        Button editButton = new Button("Edit");
        Button deleteButton = new Button("Delete");
        createButton.setOnAction(e -> createHandler.run());
        editButton.setOnAction(e -> selectedAgent(editHandler));
        deleteButton.setOnAction(e -> selectedAgent(deleteHandler));
        agentList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                selectedAgent(editHandler);
            }
        });
        agentList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> selectionHandler.accept(newValue));

        HBox buttons = new HBox(6, createButton, editButton, deleteButton);
        getChildren().addAll(new Label("Agents"), agentList, buttons);
        VBox.setVgrow(agentList, Priority.ALWAYS);
    }

    public void setOnCreate(Runnable createHandler) {
        this.createHandler = createHandler == null ? () -> { } : createHandler;
    }

    public void setOnEdit(Consumer<AgentConfig> editHandler) {
        this.editHandler = editHandler == null ? agent -> { } : editHandler;
    }

    public void setOnDelete(Consumer<AgentConfig> deleteHandler) {
        this.deleteHandler = deleteHandler == null ? agent -> { } : deleteHandler;
    }

    public void setOnSelection(Consumer<AgentConfig> selectionHandler) {
        this.selectionHandler = selectionHandler == null ? agent -> { } : selectionHandler;
    }

    public AgentConfig selectedAgent() {
        return agentList.getSelectionModel().getSelectedItem();
    }

    private void selectedAgent(Consumer<AgentConfig> action) {
        AgentConfig selected = agentList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            action.accept(selected);
        }
    }
}
