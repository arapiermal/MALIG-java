package app.core.agent;
import app.core.environment.Goal;
import app.llm.LLMService;
import java.util.*;

public class LLMAgent extends AbstractAgent {
    private final LLMService service;
    public LLMAgent(AgentProfile profile, AgentContext context, LLMService service) { super(profile, context); this.service = Objects.requireNonNull(service); }
    public LLMAgent(UUID id, AgentProfile profile, AgentContext context, LLMService service) { super(id, profile, context); this.service = Objects.requireNonNull(service); }
    @Override public boolean supportsConversation() { return true; }
    @Override protected void decide(List<Goal> goals) { complete(LLMReasoner.reason(this, goals, service)); }
}
