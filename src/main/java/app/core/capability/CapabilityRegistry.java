package app.core.capability;

import java.util.*;

public class CapabilityRegistry {
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    public void register(Capability capability) {
        if (capabilities.putIfAbsent(capability.name(), capability) != null)
            throw new IllegalArgumentException("Duplicate capability: " + capability.name());
    }
    public Optional<Capability> find(String name) { return Optional.ofNullable(capabilities.get(name)); }
    public ToolResult execute(UUID agentId, ToolCall call) {
        Capability capability = capabilities.get(call.name());
        if (capability == null) return ToolResult.failure("Capability not permitted: " + call.name());
        Optional<String> invalid = capability.schema().validate(call.arguments());
        if (invalid.isPresent()) return ToolResult.failure(invalid.get());
        try { return Objects.requireNonNull(capability.execute(agentId, call)); }
        catch (RuntimeException ex) { return ToolResult.failure("Tool failed: " + ex.getClass().getSimpleName()); }
    }
    public Collection<Capability> all() { return List.copyOf(capabilities.values()); }
}
