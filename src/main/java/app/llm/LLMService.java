package app.llm;

public interface LLMService {
    LLMOutput generate(LLMInput input);

    LLMConfig config();

    default boolean isAvailable() {
        return true;
    }
}
