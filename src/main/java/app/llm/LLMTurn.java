package app.llm;

import app.core.capability.ToolResult;
import java.util.List;

/** A model proposal and its HMAS results, in call order. Provider metadata is opaque to core. */
public record LLMTurn(LLMOutput proposal, List<ToolResult> results) {
    public LLMTurn {
        results = List.copyOf(results);
        if (proposal.toolCalls().size() != results.size()) throw new IllegalArgumentException("One result per proposed call is required");
    }
}
