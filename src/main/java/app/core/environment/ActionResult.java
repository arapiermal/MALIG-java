package app.core.environment;

import java.util.Map;

public record ActionResult(boolean success, String message, Map<String, Object> outputs) {
    public ActionResult {
        message = message == null ? "" : message;
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public static ActionResult success(String message) {
        return new ActionResult(true, message, Map.of());
    }

    public static ActionResult failure(String message) {
        return new ActionResult(false, message, Map.of());
    }
}
