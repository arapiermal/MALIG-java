package app.core.runtime;

import app.core.environment.Goal;
import java.util.*;

/** Local correlated work request, including structured data published by the recipient's invocation. */
public record AgentRequest(UUID id, UUID requesterId, UUID recipientId, Goal goal,
                           String onResult, Status status, String result, Map<String, Object> outputs,
                           Map<String, Object> observedFacts, CompletionEvidence evidence) {
    public enum CompletionEvidence { PENDING, UNVERIFIED, RESULT_VALIDATED, FAILED, CANCELLED }
    public AgentRequest(UUID id, UUID requesterId, UUID recipientId, Goal goal, String onResult, Status status, String result, Map<String, Object> outputs, Map<String, Object> observedFacts) {
        this(id, requesterId, recipientId, goal, onResult, status, result, outputs, observedFacts, switch (status) {
            case PENDING -> CompletionEvidence.PENDING; case SUCCEEDED -> CompletionEvidence.UNVERIFIED;
            case FAILED -> CompletionEvidence.FAILED; case CANCELLED -> CompletionEvidence.CANCELLED; });
    }
    public AgentRequest { outputs = Map.copyOf(outputs); observedFacts = Map.copyOf(observedFacts); }
    public AgentRequest(UUID id, UUID requesterId, UUID recipientId, Goal goal, String onResult, Status status, String result, Map<String, Object> outputs) {
        this(id, requesterId, recipientId, goal, onResult, status, result, outputs, Map.of());
    }
    public AgentRequest(UUID id, UUID requesterId, UUID recipientId, Goal goal, String onResult, Status status, String result) {
        this(id, requesterId, recipientId, goal, onResult, status, result, Map.of());
    }
    /** SUCCEEDED denotes invocation completion, not inferred semantic goal achievement. */
    public String outcomeLabel() {
        return switch (evidence) {
            case UNVERIFIED -> "RESPONSE COMPLETED - goal unverified";
            case RESULT_VALIDATED -> "RESULT VALIDATED - declared output contract met";
            default -> evidence.name();
        };
    }
    public enum Status { PENDING, SUCCEEDED, FAILED, CANCELLED }
    public AgentRequest finish(Status status, String result) { return finish(status, result, Map.of()); }
    public AgentRequest finish(Status status, String result, Map<String, Object> outputs) {
        return new AgentRequest(id, requesterId, recipientId, goal, onResult, status, result, outputs, observedFacts);
    }
}
