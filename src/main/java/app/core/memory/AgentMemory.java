package app.core.memory;

import java.util.List;

public interface AgentMemory {
    void remember(MemoryEntry entry);

    List<MemoryEntry> recallRecent(int limit);

    List<MemoryEntry> search(String query, int limit);

    /** Relevant ordered session messages; independent of the global execution trace. */
    default List<MemoryEntry> conversation(java.util.UUID session, int limit) {
        return recallRecent(1000).stream().filter(e -> session.toString().equals(e.metadata().get("conversationId")))
                .sorted(java.util.Comparator.comparingInt(e -> Integer.parseInt(e.metadata().get("sequence"))))
                .toList().reversed().stream().limit(limit).toList().reversed();
    }

    void clear();

    void putState(String key, Object value);

    java.util.Map<String, Object> state();
}
