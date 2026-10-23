package app.llm;
import app.core.capability.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LLMInput(
        String systemPrompt,
        String userPrompt,
        List<String> conversation,
        List<ToolSchema> tools,
        Map<String, String> metadata,
        List<LLMTurn> turns
) {
    public LLMInput {
        turns = turns == null ? List.of() : List.copyOf(turns);
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        userPrompt = userPrompt == null ? "" : userPrompt;
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        tools = tools == null ? List.of() : List.copyOf(tools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<LLMTurn> turns = List.of();
        public Builder turns(List<LLMTurn> turns) { this.turns = List.copyOf(turns); return this; }
        private String systemPrompt = "";
        private String userPrompt = "";
        private final List<String> conversation = new ArrayList<>();
        private final List<ToolSchema> tools = new ArrayList<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        public Builder conversation(List<String> conversation) {
            this.conversation.clear();
            if (conversation != null) {
                this.conversation.addAll(conversation);
            }
            return this;
        }

        public Builder tool(ToolSchema tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public LLMInput build() {
            return new LLMInput(systemPrompt, userPrompt, conversation, tools, metadata, turns);
        }
    }
}
