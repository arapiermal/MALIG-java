package app.core.agent;

import app.core.environment.*;
import app.core.message.MessageType;
import app.core.planning.*;
import app.core.runtime.AgentRequest;
import java.util.*;

/** Small reusable Java behavior: optional numeric condition -> peer work -> content-sensitive recording.
 * It proposes capabilities; it never executes tools or treats arbitrary prose as permission to mutate a world.
 */
public final class PeerWorkPolicy implements AgentPolicy {
    private static final String CONTINUATION = "Record configured peer result";
    private final String trigger, peer, task, fact, contains;
    private final double below;
    public PeerWorkPolicy(Map<String, String> options) {
        trigger = required(options, "trigger"); peer = required(options, "peer"); task = required(options, "task");
        fact = options.get("fact"); contains = options.get("acceptContains");
        try { below = Double.parseDouble(options.get("below")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("below must be a finite number"); }
        if (!Double.isFinite(below)) throw new IllegalArgumentException("below must be a finite number");
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Configure " + key); return value;
    }
    @Override public boolean supports(AbstractAgent a, List<Goal> goals) {
        return goals.size() == 1 && (goals.getFirst().description().equals(trigger)
                || goals.getFirst().description().equals(CONTINUATION) && a.activeMessage().filter(m -> m.type() == MessageType.RESPONSE).isPresent());
    }
    @Override public Plan createPlan(AbstractAgent a, List<Goal> goals) {
        UUID recipient = a.context().resolvePeer(peer);
        if (goals.getFirst().description().equals(CONTINUATION)) {
            UUID id = UUID.fromString(a.activeMessage().orElseThrow().metadata().get("requestId"));
            var response = a.context().requests().stream().filter(r -> r.id().equals(id) && r.requesterId().equals(a.id())
                    && r.recipientId().equals(recipient) && r.onResult().equals(CONTINUATION)).findFirst().orElseThrow();
            boolean accepted = response.status() == AgentRequest.Status.SUCCEEDED && response.result().contains(contains);
            return action(a, "remember", Map.of("content", (accepted ? "Accepted peer content" : "Rejected/failed peer content")
                    + " for " + id + ": " + response.result() + " | outputs=" + response.outputs()));
        }
        if (!fact.isBlank()) {
            Object value = a.context().environment().snapshot().facts().get(fact);
            if (!(value instanceof Number number)) throw new IllegalArgumentException("Condition requires numeric environment fact: " + fact);
            if (!(number.doubleValue() < below)) return action(a, "remember", Map.of("content", "Condition false: " + fact + " < " + below + "; no peer work requested"));
        }
        return action(a, "request_agent", Map.of("recipientId", recipient.toString(), "task", task, "onResult", CONTINUATION));
    }
    private Plan action(AbstractAgent a, String tool, Map<String, Object> args) {
        return new Plan("conditional-peer-work", List.of(new PlanStep("decision", tool, new AgentAction(a.id(), tool, args))));
    }
}
