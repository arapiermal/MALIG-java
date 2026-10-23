package app.core.runtime;

import java.time.Instant;
import java.util.UUID;

/** User-directed draft/review/revision, not an autonomous delegation policy. */
public final class CollaborationTask {
    public enum Status { DRAFTING, REVIEWING, REVISING, COMPLETED, FAILED, CANCELLED }
    final UUID id = UUID.randomUUID();
    final UUID initiator, reviewer;
    final String task;
    final Instant started = Instant.now();
    Instant finished;
    UUID goalId, requestId;
    Status status = Status.DRAFTING;
    String draft = "", feedback = "", result = "";
    CollaborationTask(UUID initiator, UUID reviewer, String task) {
        this.initiator = initiator; this.reviewer = reviewer; this.task = task;
    }
    boolean active() { return status == Status.DRAFTING || status == Status.REVIEWING || status == Status.REVISING; }
    Snapshot snapshot() { return new Snapshot(id, initiator, reviewer, task, status, requestId, draft, feedback, result, started, finished); }
    public record Snapshot(UUID id, UUID initiator, UUID reviewer, String task, Status status, UUID requestId,
                           String draft, String feedback, String result, Instant started, Instant finished) {
        public boolean active() { return finished == null; }
        @Override public String toString() { return status + " | " + task.substring(0, Math.min(task.length(), 65)); }
    }
}
