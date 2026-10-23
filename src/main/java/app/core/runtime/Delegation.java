package app.core.runtime;
import app.core.environment.Goal;
import java.util.UUID;

public record Delegation(UUID id, UUID parentId, UUID childId, Goal goal, Status status, String result) {
    public enum Status { PENDING, SUCCEEDED, FAILED, CANCELLED }
    public Delegation finish(Status status, String result) { return new Delegation(id, parentId, childId, goal, status, result); }
}
