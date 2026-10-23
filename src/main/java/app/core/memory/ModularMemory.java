package app.core.memory;

import java.util.*;

/** Bounded context, episodic retrieval, and structured state with a replaceable storage backend. */
public final class ModularMemory implements AgentMemory {
    private final MemoryStore context;
    private final MemoryStore episodes;
    private final Map<String, Object> state = new LinkedHashMap<>();
    private int capacity;
    private boolean contextEnabled;
    private boolean episodesEnabled;

    public ModularMemory(int capacity, boolean contextEnabled, boolean episodesEnabled) {
        this(new InMemoryStore(), new InMemoryStore(), capacity, contextEnabled, episodesEnabled);
    }

    public ModularMemory(MemoryStore context, MemoryStore episodes, int capacity,
                         boolean contextEnabled, boolean episodesEnabled) {
        this.context = Objects.requireNonNull(context);
        this.episodes = Objects.requireNonNull(episodes);
        configure(capacity, contextEnabled, episodesEnabled);
    }

    public synchronized void configure(int capacity, boolean contextEnabled, boolean episodesEnabled) {
        if (capacity < 1) throw new IllegalArgumentException("Memory capacity must be positive");
        this.capacity = capacity;
        this.contextEnabled = contextEnabled;
        this.episodesEnabled = episodesEnabled;
        context.retainNewest(capacity);
    }

    @Override public synchronized void remember(MemoryEntry entry) {
        if (contextEnabled) { context.save(entry); context.retainNewest(capacity); }
        if (episodesEnabled && !entry.type().equals("observation")) {
            episodes.save(entry);
            episodes.retainNewest(Math.max(1000, capacity));
        }
    }

    @Override public synchronized List<MemoryEntry> recallRecent(int limit) {
        return contextEnabled ? context.findRecent(Math.min(Math.max(0, limit), capacity)) : List.of();
    }

    @Override public synchronized List<MemoryEntry> search(String query, int limit) {
        Map<UUID, MemoryEntry> unique = new LinkedHashMap<>();
        if (contextEnabled) context.search(query, limit).forEach(e -> unique.put(e.id(), e));
        if (episodesEnabled) episodes.search(query, limit).forEach(e -> unique.put(e.id(), e));
        return InMemoryStore.rank(unique.values(), query, limit);
    }

    @Override public synchronized List<MemoryEntry> conversation(UUID session, int limit) {
        Map<UUID, MemoryEntry> entries = new LinkedHashMap<>();
        if (contextEnabled) context.findRecent(capacity).forEach(e -> entries.put(e.id(), e));
        if (episodesEnabled) episodes.findRecent(Math.max(1000, capacity)).forEach(e -> entries.put(e.id(), e));
        var ordered = entries.values().stream().filter(e -> session.toString().equals(e.metadata().get("conversationId")))
                .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.metadata().get("sequence")))).toList();
        return List.copyOf(ordered.subList(Math.max(0, ordered.size() - Math.max(0, limit)), ordered.size()));
    }
    public synchronized List<MemoryEntry> episodes(int limit) { return episodes.findRecent(limit); }
    @Override public synchronized void putState(String key, Object value) { state.put(key, value); }
    @Override public synchronized Map<String, Object> state() { return Map.copyOf(state); }
    @Override public synchronized void clear() { context.clear(); episodes.clear(); state.clear(); }
}
