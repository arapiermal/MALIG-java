package app.core.runtime;

import app.core.environment.Goal;
import app.llm.LLMStatus;
import java.time.Instant;
import java.util.*;

/** Session history for explicitly submitted user goals. Observes execution; never schedules it. */
public record UserTask(UUID agentId, String agentName, Goal goal, Status status, Instant submittedAt,
                       Instant startedAt, Instant finishedAt, String result, Map<String, Object> outputs, LLMStatus provider) {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public UserTask { outputs = freezeMap(outputs); }
    private static Map<String, Object> freezeMap(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(); values.forEach((k,v) -> copy.put(k,freeze(v))); return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object,Object> copy = new LinkedHashMap<>(); map.forEach((k,v) -> copy.put(k,freeze(v))); return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(UserTask::freeze).toList();
        if (value instanceof Set<?> set) { Set<Object> copy = new LinkedHashSet<>(); set.forEach(v -> copy.add(freeze(v))); return Collections.unmodifiableSet(copy); }
        return value; // Existing domain records/scalars are immutable; custom Java objects remain trusted extensions.
    }
    public UUID id() { return goal.id(); }
    public boolean terminal() { return status != Status.PENDING && status != Status.RUNNING; }
    public String outcomeLabel() { return status == Status.SUCCEEDED ? "INVOCATION COMPLETED - goal achievement unverified" : status.name(); }
    UserTask started(Instant at) { return new UserTask(agentId, agentName, goal, Status.RUNNING, submittedAt, at, null, "", Map.of(), provider); }
    UserTask finished(Status state, Instant at, String result, Map<String, Object> outputs, LLMStatus provider) {
        return new UserTask(agentId, agentName, goal, state, submittedAt, startedAt, at, result, outputs, provider);
    }
}
