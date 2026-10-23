package app.core.memory;

import java.util.*;
import java.util.stream.Collectors;

/** Local keyword retrieval. MemoryStore can be replaced with an embedding-backed implementation. */
public class InMemoryStore implements MemoryStore {
    private final List<MemoryEntry> entries = new ArrayList<>();
    @Override public synchronized void save(MemoryEntry entry) { entries.add(Objects.requireNonNull(entry)); }
    @Override public synchronized List<MemoryEntry> findRecent(int limit) {
        return entries.stream().sorted(Comparator.comparing(MemoryEntry::createdAt).reversed())
                .limit(Math.max(0, limit)).toList();
    }
    @Override public synchronized List<MemoryEntry> search(String query, int limit) { return rank(entries, query, limit); }
    @Override public synchronized void clear() { entries.clear(); }
    @Override public synchronized void retainNewest(int capacity) {
        if (entries.size() > capacity) entries.subList(0, entries.size() - Math.max(0, capacity)).clear();
    }
    public static List<MemoryEntry> rank(Collection<MemoryEntry> entries, String query, int limit) {
        Set<String> terms = tokens(query);
        if (terms.isEmpty()) return List.of();
        return entries.stream().filter(e -> score(e, terms) > 0)
                .sorted(Comparator.<MemoryEntry>comparingDouble(e -> score(e, terms)).reversed()
                        .thenComparing(MemoryEntry::createdAt, Comparator.reverseOrder()))
                .limit(Math.max(0, limit)).toList();
    }
    private static double score(MemoryEntry entry, Set<String> query) {
        Set<String> words = tokens(entry.content() + " " + entry.source());
        long matches = query.stream().filter(words::contains).count();
        return matches / Math.sqrt(Math.max(1, words.size()));
    }
    private static Set<String> tokens(String text) {
        Set<String> stop = Set.of("the", "and", "for", "with", "this", "that", "from", "into", "a", "an", "to", "of", "is");
        return Arrays.stream((text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isBlank() && !stop.contains(s)).collect(Collectors.toSet());
    }
}
