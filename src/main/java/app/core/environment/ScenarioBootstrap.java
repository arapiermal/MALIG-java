package app.core.environment;

import app.core.agent.*;
import app.core.runtime.AgentRuntime;
import app.llm.LLMConfig;
import java.util.List;

/** Small application composition point; no UI or provider SDK dependencies. */
public interface ScenarioBootstrap {
    String name();
    EnvironmentContext createEnvironment();
    void configure(AgentFactory factory);
    default List<AbstractAgent> loadDemo(AgentRuntime runtime, AgentFactory factory, LLMConfig analysisProvider) { return List.of(); }
}
