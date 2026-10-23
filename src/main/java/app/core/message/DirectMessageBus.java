package app.core.message;

import java.util.*;

/** One transport supports both direct delivery and explicit topic subscriptions. */
public final class DirectMessageBus implements MessageBus {
    private final Map<UUID, MessageQueue> queues = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> subscribers = new LinkedHashMap<>();
    @Override public synchronized boolean hasPendingMessages() { return !queues.isEmpty(); }
    @Override public synchronized void send(AgentMessage message) {
        if (message.recipientId() != null) {
            queues.computeIfAbsent(message.recipientId(), ignored -> new MessageQueue()).add(message);
        } else if (message.topic() != null) {
            for (UUID id : subscribers.getOrDefault(message.topic(), Set.of())) send(new AgentMessage(
                    message.id(), message.senderId(), id, message.topic(), message.type(), message.priority(),
                    message.content(), message.createdAt(), message.metadata()));
        } else throw new IllegalArgumentException("Message needs a recipient or topic");
    }
    @Override public synchronized List<AgentMessage> receive(UUID id) {
        MessageQueue queue = queues.remove(id);
        return queue == null ? List.of() : queue.drain();
    }
    @Override public synchronized void subscribe(UUID id, String topic) {
        subscribers.computeIfAbsent(topic, ignored -> new LinkedHashSet<>()).add(id);
    }
    @Override public synchronized void remove(UUID id) {
        queues.remove(id);
        subscribers.values().forEach(ids -> ids.remove(id));
    }
}
