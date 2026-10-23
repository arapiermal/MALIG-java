package app.core.message;
import java.util.*;

public interface MessageBus {
    void send(AgentMessage message);
    List<AgentMessage> receive(UUID agentId);
    void subscribe(UUID agentId, String topic);
    void remove(UUID agentId);
    boolean hasPendingMessages();
}
