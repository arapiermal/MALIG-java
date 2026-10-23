package com.erimali.multiAgentFX.panel;

import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

public class ConsolePane extends VBox {
    private final TextArea logArea = new TextArea();
    private final TextField inputField = new TextField();
    private Consumer<String> commandHandler = command -> { };

    public ConsolePane() {
        setSpacing(4);
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPromptText("Simulation logs and agent messages will appear here...");
        inputField.setPromptText("Enter a command or agent message...");

        Button sendButton = new Button("Send");
        Button clearButton = new Button("Clear");
        sendButton.setOnAction(e -> submitCommand());
        clearButton.setOnAction(e -> clear());
        inputField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                submitCommand();
                event.consume();
            }
        });

        HBox inputBox = new HBox(8, inputField, sendButton, clearButton);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        getChildren().addAll(logArea, inputBox);
        VBox.setVgrow(logArea, Priority.ALWAYS);
    }

    public void setOnCommand(Consumer<String> commandHandler) {
        this.commandHandler = commandHandler == null ? command -> { } : commandHandler;
    }

    public void appendLog(String message) {
        logArea.appendText(message + System.lineSeparator());
        if (logArea.getLength() > 100000) logArea.deleteText(0, logArea.getLength() - 80000);
    }

    public void clear() {
        logArea.clear();
    }

    private void submitCommand() {
        String command = inputField.getText().trim();
        if (command.isEmpty()) {
            return;
        }
        inputField.clear();
        commandHandler.accept(command);
    }
}
