package app.llm.provider;

import app.llm.*;
import app.core.capability.ToolCall;
import java.util.*;

/** Explicit offline test adapter. It is a deterministic substitute, not a local language model. */
public class DeterministicLLMService extends AbstractLLMProvider {
    public DeterministicLLMService() {
        super(new LLMConfig("offline", "offline-rules", 0, 512, Map.of()));
    }
    @Override public LLMOutput generate(LLMInput input) {
        boolean recommends = input.tools().stream().anyMatch(s -> s.name().equals("recommend_resources"));
        if (recommends && !input.turns().isEmpty() && input.turns().stream().flatMap(t -> t.proposal().toolCalls().stream())
                .noneMatch(c -> c.name().equals("evaluate_resources"))) {
            Object facts = input.turns().stream().flatMap(t -> t.results().stream()).map(r -> r.outputs().get("facts"))
                    .filter(Objects::nonNull).findFirst().orElse(Map.of());
            Object target = facts instanceof Map<?, ?> map && map.containsKey("desiredResourceTarget") ? map.get("desiredResourceTarget") : 80;
            return new LLMOutput("Offline target calculation", List.of(new ToolCall("evaluate_resources", Map.of("target", target))), Map.of());
        }
        if (!input.turns().isEmpty() && recommends && input.turns().stream()
                .flatMap(t -> t.proposal().toolCalls().stream()).noneMatch(c -> c.name().equals("recommend_resources"))) {
            double adjustment = input.turns().stream().flatMap(t -> t.results().stream())
                    .map(r -> r.outputs().get("adjustment")).filter(Number.class::isInstance).map(Number.class::cast)
                    .mapToDouble(Number::doubleValue).findFirst().orElse(0);
            return new LLMOutput("Offline deterministic recommendation", List.of(new ToolCall("recommend_resources",
                    Map.of("intervene", adjustment > 0, "reason", "Offline test rule: act only for a positive resource shortage"))), Map.of());
        }
        if (input.conversation().stream().anyMatch(s -> s.startsWith("Tool result for ")))
            return LLMOutput.text("Offline analysis: " + input.conversation().getLast());
        var available = input.tools().stream().map(s -> s.name()).toList();
        String prompt = input.userPrompt().toLowerCase(Locale.ROOT);
        List<ToolCall> calls = new ArrayList<>();
        if (available.contains("inspect_environment")) calls.add(new ToolCall("inspect_environment", Map.of()));
        if (!recommends && prompt.contains("resource") && available.contains("evaluate_resources"))
            calls.add(new ToolCall("evaluate_resources", Map.of("target", 80)));
        return new LLMOutput("Offline analysis: inspect available evidence and evaluate the requested task.",
                calls, Map.of("provider", "offline", "model", "offline-rules", "mode", "OFFLINE_TEST"));
    }
}
