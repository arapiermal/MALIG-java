package com.erimali.multiAgentFX.bridge;

import app.core.agent.*;
import app.core.environment.*;
import app.core.message.DirectMessageBus;
import app.core.runtime.*;
import app.llm.*;
import app.llm.provider.LLMServiceFactory;
import java.util.*;

/** Uses the same factory, agent and tool loop as the application. Tests can inject a scripted service. */
public final class LLMTestFacade {
    private final LLMService override;
    public LLMTestFacade() { override = null; }
    public LLMTestFacade(LLMService service) { override = service; }
    public String prompt(String text) { return test(LLMServiceFactory.defaults(), text, false); }
    public String test(LLMConfig config, String text, boolean tools) {
        TestResult result = run(config, text, tools);
        return result.provider() + "\nAgent outcome: " + result.status() + "\n" + result.response() + "\n\nExecution:\n" + result.traceText();
    }
    public record TestResult(LLMStatus provider, AgentStatus status, String response, List<ExecutionEvent> events) {
        public String traceText() { return events.stream().map(e -> e.type() + ": " + e.message() + " " + e.metadata()).collect(java.util.stream.Collectors.joining("\n\n")); }
    }
    public TestResult run(LLMConfig config, String text, boolean tools) {
        AgentRuntime runtime = new AgentRuntime(new SequentialScheduler(), EnvironmentContext.empty(), new DirectMessageBus());
        AgentFactory factory = new AgentFactory(runtime, c -> override == null
                ? LLMServiceFactory.forConfig(c) : override);
        AbstractAgent agent = factory.create(UUID.randomUUID(), AgentProfile.named("Provider verification"),
                new AgentConfiguration(AgentType.LLM, "auto", 20, true, true,
                        tools ? Set.of("inspect_environment", "remember") : Set.of(), config), null);
        runtime.register(agent); runtime.submitGoal(agent.id(), Goal.of(text)); runtime.step();
        return new TestResult(agent.llmStatus(), agent.status(), agent.lastOutcome(), runtime.trace().recent(100));
    }
    public String modelName() { return override == null ? LLMServiceFactory.defaults().model() : override.config().model(); }
}
