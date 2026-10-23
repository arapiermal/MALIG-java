package app.core.capability;

import java.util.Map;

public record ToolResult(boolean success, String message, Map<String, Object> outputs) {
    public ToolResult {
        message = message == null ? "" : message;
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }
    public static ToolResult success(String message, Map<String, Object> outputs) { return new ToolResult(true, message, outputs); }
    public static ToolResult failure(String message) { return new ToolResult(false, message, Map.of()); }
}
