package app.core.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class ExecutionTrace {
    private final java.util.concurrent.atomic.AtomicLong listenerFailures = new java.util.concurrent.atomic.AtomicLong();
    public long listenerFailures() { return listenerFailures.get(); }
    private final int capacity;
    private final List<ExecutionEvent> events = new ArrayList<>();
    private final List<Consumer<ExecutionEvent>> listeners = new CopyOnWriteArrayList<>();

    public ExecutionTrace() {
        this(500);
    }

    public ExecutionTrace(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    public void publish(ExecutionEvent event) {
        synchronized (events) {
            events.add(event);
            if (events.size() > capacity) {
                events.removeFirst();
            }
        }
        listeners.forEach(listener -> { try { listener.accept(event); } catch (RuntimeException ignored) { listenerFailures.incrementAndGet(); } });
    }

    public List<ExecutionEvent> recent(int limit) {
        synchronized (events) {
            int from = Math.max(0, events.size() - Math.max(0, limit));
            return List.copyOf(events.subList(from, events.size()));
        }
    }

    public AutoCloseable subscribe(Consumer<ExecutionEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
