package app.core.runtime;

import app.core.environment.Goal;
import java.util.*;

/** Opt-in delivery barrier for existing peer requests, not a workflow or an achievement validator. */
public record RequestWait(UUID id, UUID ownerId, List<UUID> requestIds, Goal continuation, Status status, String result) {
    public enum Status { WAITING, QUEUED, COMPLETED, FAILED, CANCELLED }
    public RequestWait { requestIds = List.copyOf(requestIds); result = result == null ? "" : result; }
    public RequestWait(UUID id, UUID ownerId, List<UUID> requestIds, Goal continuation, Status status) { this(id, ownerId, requestIds, continuation, status, ""); }
    public RequestWait withStatus(Status status) { return finish(status, result); }
    public RequestWait finish(Status status, String result) { return new RequestWait(id, ownerId, requestIds, continuation, status, result); }
}
