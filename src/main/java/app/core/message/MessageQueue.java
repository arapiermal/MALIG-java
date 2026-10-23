package app.core.message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class MessageQueue {
    private final PriorityQueue<AgentMessage> queue = new PriorityQueue<>(
            Comparator.comparing(AgentMessage::priority).reversed()
                    .thenComparing(AgentMessage::createdAt)
    );

    public synchronized void add(AgentMessage message) {
        queue.add(message);
    }

    public synchronized List<AgentMessage> drain() {
        List<AgentMessage> messages = new ArrayList<>();
        while (!queue.isEmpty()) {
            messages.add(queue.poll());
        }
        return messages;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }
}
