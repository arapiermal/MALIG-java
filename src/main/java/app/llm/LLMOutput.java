package app.llm;
import app.core.capability.ToolCall;

import java.util.List;
import java.util.Map;

public record LLMOutput(String text, List<ToolCall> toolCalls, Map<String, String> metadata) {
    public LLMOutput {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static LLMOutput text(String text) {
        return new LLMOutput(text, List.of(), Map.of());
    }
}
