package com.erimali.multiAgentFX.panel;

import app.core.agent.AbstractAgent;
import app.core.environment.Goal;
import com.erimali.multiAgentFX.bridge.RuntimeFacade;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

public final class OrganizationPane extends VBox {
    public OrganizationPane(RuntimeFacade facade) {
        setSpacing(8); setPadding(new javafx.geometry.Insets(6));
        TreeView<String> tree = new TreeView<>();
        ComboBox<AbstractAgent> parent = new ComboBox<>(), child = new ComboBox<>();
        parent.setPromptText("Requester / supervisor"); child.setPromptText("Recipient / child");
        parent.setMaxWidth(Double.MAX_VALUE); child.setMaxWidth(Double.MAX_VALUE);
        Button assign = new Button("Assign"), detach = new Button("Detach");
        TextField goal = new TextField(); goal.setPromptText("Goal for recipient");
        Button delegate = new Button("Delegate to child");
        Button requestPeer = new Button("Request peer work");
        requestPeer.setOnAction(e -> {
            if (parent.getValue() != null && child.getValue() != null && !goal.getText().isBlank()) {
                UUID requester = parent.getValue().id(), recipient = child.getValue().id(); String text = goal.getText();
                facade.runAsync(() -> { facade.runtime().request(requester, recipient, Goal.of(text), "");
                    facade.drainWork(); });
            }
        });
        TextArea tasks = new TextArea(); tasks.setEditable(false); tasks.setPrefRowCount(4); tasks.setWrapText(true);
        Runnable refresh = () -> {
            List<AbstractAgent> agents = List.copyOf(facade.runtime().agents());
            AbstractAgent p = parent.getValue(), c = child.getValue();
            parent.getItems().setAll(agents); child.getItems().setAll(agents);
            if (p != null) agents.stream().filter(a -> a.id().equals(p.id())).findFirst().ifPresent(parent::setValue);
            if (c != null) agents.stream().filter(a -> a.id().equals(c.id())).findFirst().ifPresent(child::setValue);
            Map<UUID, TreeItem<String>> items = new LinkedHashMap<>();
            agents.forEach(a -> items.put(a.id(), new TreeItem<>(a.name() + " [" + a.profile().role() + "]")));
            TreeItem<String> root = new TreeItem<>("HMAS organization"); root.setExpanded(true);
            var hierarchy = facade.runtime().hierarchy();
            items.forEach((id, item) -> {
                item.setExpanded(true);
                items.getOrDefault(hierarchy.get(id), root).getChildren().add(item);
            });
            tree.setRoot(root);
            tasks.setText(facade.runtime().delegations().stream()
                    .map(t -> t.status() + ": " + t.goal().description() + "\n" + t.result())
                    .collect(java.util.stream.Collectors.joining("\n")) +
                    "\n\nPeer requests (no hierarchy required):\n" + facade.runtime().requests().stream()
                    .map(r -> r.id() + "\n" + r.requesterId() + " -> " + r.recipientId() + "\n" + r.status() + ": " + r.goal().description() + "\n" + r.result())
                    .collect(java.util.stream.Collectors.joining("\n")));
        };
        assign.setOnAction(e -> {
            if (parent.getValue() != null && child.getValue() != null) {
                UUID p = parent.getValue().id(), c = child.getValue().id();
                facade.runAsync(() -> facade.runtime().setParent(c, p));
            }
        });
        detach.setOnAction(e -> {
            if (child.getValue() != null) { UUID c = child.getValue().id(); facade.runAsync(() -> facade.runtime().setParent(c, null)); }
        });
        delegate.setOnAction(e -> {
            if (parent.getValue() != null && child.getValue() != null && !goal.getText().isBlank()) {
                UUID p = parent.getValue().id(), c = child.getValue().id(); String text = goal.getText();
                facade.runAsync(() -> { facade.runtime().delegate(p, c, Goal.of(text)); facade.runtime().step(); });
            }
        });
        facade.onEvent(e -> {
            if (Set.of(app.core.runtime.ExecutionEventType.AGENT_CREATED, app.core.runtime.ExecutionEventType.AGENT_UPDATED,
                    app.core.runtime.ExecutionEventType.AGENT_REMOVED, app.core.runtime.ExecutionEventType.HIERARCHY_CHANGED,
                    app.core.runtime.ExecutionEventType.TASK_COMPLETED, app.core.runtime.ExecutionEventType.TASK_DELEGATED,
                    app.core.runtime.ExecutionEventType.REQUEST_SENT, app.core.runtime.ExecutionEventType.REQUEST_COMPLETED).contains(e.type()))
                Platform.runLater(refresh);
        });
        getChildren().addAll(tree, parent, child, new HBox(6, assign, detach), goal, requestPeer, delegate, tasks);
        VBox.setVgrow(tree, Priority.ALWAYS); refresh.run();
    }
}
