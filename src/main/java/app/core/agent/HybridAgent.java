package app.core.agent;

import app.core.environment.Goal;
import app.llm.LLMService;
import java.util.*;

/** Optional internal composition. Heterogeneous cooperation, not this class, defines HMAS hybridity. */
public final class HybridAgent extends AbstractAgent {
    private final AgentPolicy classicalPolicy;
    private final LLMService service;
    private volatile ReasoningPath lastReasoningPath = ReasoningPath.NONE;
    public HybridAgent(AgentProfile profile, AgentContext context, AgentPolicy policy, LLMService service) {
        this(UUID.randomUUID(), profile, context, policy, service);
    }
    public HybridAgent(UUID id, AgentProfile profile, AgentContext context, AgentPolicy policy, LLMService service) {
        super(id, profile, context);
        this.classicalPolicy = Objects.requireNonNull(policy); this.service = Objects.requireNonNull(service);
    }
    @Override protected void decide(List<Goal> goals) {
        if (classicalPolicy.supports(this, goals)) {
            lastReasoningPath = ReasoningPath.DETERMINISTIC;
            complete(new app.core.planning.PlanExecutor().execute(classicalPolicy.createPlan(this, goals), this, context()));
        } else {
            lastReasoningPath = ReasoningPath.LLM_FALLBACK;
            complete(LLMReasoner.reason(this, goals, service));
        }
    }
    public ReasoningPath lastReasoningPath() { return lastReasoningPath; }
    public enum ReasoningPath { NONE, DETERMINISTIC, LLM_FALLBACK }
}
