package com.erimali.multiAgentFX.agenteditor;

import com.erimali.multiAgentFX.model.AgentConfig;
import app.core.agent.AgentType;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public class AgentGeneralPane extends VBox {
    private final TextField roleField = new TextField();
    private final TextField nameField = new TextField();
    private final ComboBox<AgentType> typeBox = new ComboBox<>();
    private final TextField idField = new TextField();
    private final TextArea descriptionArea = new TextArea();
    private final CheckBox enabledBox = new CheckBox("Enabled");

    public AgentGeneralPane() {
        setSpacing(12);
        setPadding(new javafx.geometry.Insets(10));
        typeBox.setItems(FXCollections.observableArrayList(AgentType.values()));
        descriptionArea.setPrefRowCount(5);
        descriptionArea.setWrapText(true);
        idField.setEditable(false);

        GridPane grid = FormLayout.createFormGrid();
        int r = 0;
        FormLayout.addRow(grid, r++, "Name:", nameField);
        FormLayout.addRow(grid, r++, "Controller:", typeBox);
        FormLayout.addRow(grid, r++, "UUID:", idField);
        FormLayout.addRow(grid, r++, "Role:", roleField);
        FormLayout.addRow(grid, r++, "Description:", descriptionArea);
        FormLayout.addRow(grid, r, "", enabledBox);

        TitledPane pane = new TitledPane("Identity", grid);
        pane.setCollapsible(false);
        getChildren().add(pane);
        var help = new javafx.scene.control.Label("Controller selects how this agent reasons. Role and description explain its purpose and supported tasks; they do not implement behavior. Configure the policy/planner in Behavior / Planner, then grant its required operations in Tools. A capability performs one validated operation; it is not an autonomous agent.");
        help.setWrapText(true); getChildren().add(help);
    }

    public void loadFrom(AgentConfig config) {
        nameField.setText(config.getName());
        roleField.setText(config.getRole());
        typeBox.setValue(config.getType());
        idField.setText(config.getId().toString());
        descriptionArea.setText(config.getDescription());
        enabledBox.setSelected(config.isEnabled());
    }

    public void writeTo(AgentConfig config) {
        config.setName(nameField.getText().trim());
        config.setRole(roleField.getText().trim());
        config.setType(typeBox.getValue());
        config.setDescription(descriptionArea.getText().trim());
        config.setEnabled(enabledBox.isSelected());
    }

    public List<String> validateInput() {
        List<String> errors = new ArrayList<>();
        if (nameField.getText() == null || nameField.getText().isBlank()) {
            errors.add("Agent name is required.");
        }
        if (typeBox.getValue() == null) {
            errors.add("Agent type must be selected.");
        }
        return errors;
    }

    public javafx.beans.property.ObjectProperty<AgentType> typeProperty() { return typeBox.valueProperty(); }
    public StringProperty nameTextProperty() {
        return nameField.textProperty();
    }
}
