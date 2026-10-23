package app.scenario.synthetic;

import app.core.agent.AgentFactory;
import app.core.message.DirectMessageBus;
import app.core.runtime.*;
import app.llm.*;
import java.util.Map;

public final class HMASDemo {
    public static void main(String[] args) {
        ResourceScenario scenario = new ResourceScenario();
        AgentRuntime runtime = new AgentRuntime(new SequentialScheduler(), scenario.createEnvironment(), new DirectMessageBus());
        AgentFactory factory = ResourceScenario.factory(runtime);
        // Online access is opt-in; otherwise this demonstrator is always explicitly offline.
        LLMConfig analysis = args.length > 0 && args[0].equals("--google")
                ? new LLMConfig("google", args.length > 1 ? args[1] : "gemini-2.5-flash", 0.2, 1024, Map.of())
                : new LLMConfig("offline", "offline-rules", 0, 1024, Map.of());
        var agents = scenario.loadDemo(runtime, factory, analysis);
        for (int i = 0; i < 6; i++) runtime.step();
        System.out.println("Analysis mode: " + analysis.provider());
        System.out.println(runtime.environment().snapshot().summary());
        runtime.requests().forEach(System.out::println);
        agents.forEach(a -> System.out.println(a.name() + " " + a.status() + " " + a.llmStatus()));
    }
}
