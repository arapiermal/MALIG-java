package app.core.runtime;

import app.core.message.AgentMessage;
import java.util.*;

/** Runtime-owned turn state; callers receive immutable snapshots, never a second executor. */
public final class ConversationSession {
    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }
    public record Snapshot(UUID id, List<UUID> participants, String topic, int maxTurns, int completedTurns,
                           Status status, String detail, List<AgentMessage> messages) {
        public Snapshot { participants = List.copyOf(participants); messages = List.copyOf(messages); }
        public boolean active() { return status == Status.RUNNING; }
        @Override public String toString() { return id + " | " + status + " | " + completedTurns + "/" + maxTurns; }
    }
    final UUID id = UUID.randomUUID();
    final List<UUID> participants;
    final String topic;
    final int maxTurns;
    int completedTurns;
    Status status = Status.RUNNING;
    String detail = "";
    final List<AgentMessage> messages = new ArrayList<>();
    AgentMessage pending;
    boolean delivered;
    ConversationSession(List<UUID> participants, String topic, int maxTurns) {
        this.participants = List.copyOf(participants); this.topic = topic; this.maxTurns = maxTurns;
    }
    Snapshot snapshot() { return new Snapshot(id, participants, topic, maxTurns, completedTurns, status, detail, messages); }
}
