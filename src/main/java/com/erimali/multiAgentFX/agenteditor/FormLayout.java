package com.erimali.multiAgentFX.agenteditor;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

final class FormLayout {
    private FormLayout() {
    }

    static GridPane createFormGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(130);
        c1.setHalignment(HPos.RIGHT);

        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);

        grid.getColumnConstraints().addAll(c1, c2);
        return grid;
    }

    static void addRow(GridPane grid, int row, String labelText, Node node) {
        if (labelText != null && !labelText.isBlank()) {
            grid.add(new Label(labelText), 0, row);
        }
        grid.add(node, 1, row);
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }
}
