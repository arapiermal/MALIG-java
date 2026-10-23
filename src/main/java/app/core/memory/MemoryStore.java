package app.core.memory;

import java.util.List;

public interface MemoryStore {
    void save(MemoryEntry entry);

    List<MemoryEntry> findRecent(int limit);

    List<MemoryEntry> search(String query, int limit);

    void clear();

    void retainNewest(int capacity);
}
