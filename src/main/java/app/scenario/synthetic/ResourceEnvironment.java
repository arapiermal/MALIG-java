package app.scenario.synthetic;

import app.core.environment.*;
import app.core.capability.*;
import java.time.Instant;
import java.util.*;

/** The existing shared-facts demo isolated behind the HMAS environment boundary. */
public final class ResourceEnvironment implements EnvironmentContext {
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private final Map<String, Region> regions = new LinkedHashMap<>();
    private int transportRemaining;
    private long revision;
    private record Region(int food, int foodNeed, int energy, int energyNeed) {
        int reserve(String resource) { return resource.equals("food") ? food : energy; }
        int need(String resource) { return resource.equals("food") ? foodNeed : energyNeed; }
        Region change(String resource, int amount) { return resource.equals("food") ? new Region(food + amount, foodNeed, energy, energyNeed) : new Region(food, foodNeed, energy + amount, energyNeed); }
        Map<String, Object> attributes() { return Map.of("food", food, "foodNeed", foodNeed, "energy", energy, "energyNeed", energyNeed); }
    }
    /** Immutable candidate computed by the deterministic scenario algorithm, not by a provider. */
    public record TransferOption(String id, String from, String to, String resource, int amount, long revision) {
        public Map<String, Object> arguments() { return Map.of("from", from, "to", to, "resource", resource, "amount", amount, "revision", revision); }
        public Map<String, Object> data() { var data = new LinkedHashMap<>(arguments()); data.put("optionId", id); return Map.copyOf(data); }
    }
    public ResourceEnvironment() { this(Map.of()); }
    public ResourceEnvironment(Map<String, Object> initialFacts) {
        facts.put("simulationTick", 0L);
        facts.put("resourceLevel", 65);
        facts.put("alert", false);
        facts.putAll(initialFacts);
        if (!(facts.get("resourceLevel") instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 100)
            throw new IllegalArgumentException("resourceLevel must be an integer in [0,100]");
        if (!(facts.get("alert") instanceof Boolean) || !(facts.get("simulationTick") instanceof Number tick)
                || !Double.isFinite(tick.doubleValue()) || tick.doubleValue() < 0 || tick.doubleValue() != Math.rint(tick.doubleValue()))
            throw new IllegalArgumentException("Invalid initial alert or simulation tick");
    }
    @Override public synchronized WorldStateSnapshot snapshot() {
        Map<String, Object> observed = new LinkedHashMap<>(facts);
        if (!regions.isEmpty()) {
            observed.put("transportRemaining", transportRemaining); observed.put("regionalRevision", revision);
            for (String resource : List.of("food", "energy")) observed.put(resource + "Shortage", regions.values().stream().mapToInt(r -> Math.max(0, r.need(resource) - r.reserve(resource))).sum());
        }
        var entities = regions.entrySet().stream().map(e -> new WorldEntity(UUID.nameUUIDFromBytes(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "region", e.getKey(), e.getValue().attributes())).toList();
        return new WorldStateSnapshot(Instant.now(), entities, observed);
    }
    /** Explicit host initialization. Resets the small demonstration, never exposed as an agent tool. */
    public synchronized void configureRegions(int foodShortage, int energyShortage, int capacity) {
        if (foodShortage < 0 || foodShortage > 80 || energyShortage < 0 || energyShortage > 70 || capacity < 0 || capacity > 100)
            throw new IllegalArgumentException("Food shortage 0..80, energy shortage 0..70, transport 0..100 required");
        regions.clear();
        regions.put("Alder", new Region(130, 80, 70, 70));
        regions.put("Birch", new Region(80, 80, 120, 70));
        regions.put("Cedar", new Region(80 - foodShortage, 80, 70 - energyShortage, 70));
        transportRemaining = capacity; revision++;
    }
    public synchronized List<TransferOption> transferOptions() {
        List<TransferOption> result = new ArrayList<>();
        for (String resource : List.of("food", "energy")) for (var from : regions.entrySet()) for (var to : regions.entrySet()) {
            if (from.getKey().equals(to.getKey())) continue;
            int amount = Math.min(transportRemaining, Math.min(from.getValue().reserve(resource) - from.getValue().need(resource), to.getValue().need(resource) - to.getValue().reserve(resource)));
            if (amount > 0) result.add(new TransferOption(revision + ":" + from.getKey() + ":" + to.getKey() + ":" + resource, from.getKey(), to.getKey(), resource, amount, revision));
        }
        return List.copyOf(result);
    }
    /** Host scenario setup, never exposed as an agent capability. */
    public synchronized void configureDemo(int initial, int target) {
        if (initial < 0 || initial > 100 || target < 0 || target > 100) throw new IllegalArgumentException("Levels must be 0..100");
        regions.clear(); transportRemaining = 0; revision++;
        facts.put("resourceLevel", initial); facts.put("desiredResourceTarget", target); facts.put("alert", false);
    }
    @Override public List<ToolSchema> actions() {
        return List.of(ToolSchema.of("adjust_resources", "Change resource level by an integer amount, keeping it within 0..100.",
                        Map.of("amount", "integer")),
                ToolSchema.of("set_alert", "Set the resource alert.", Map.of("alert", "boolean")),
                ToolSchema.of("transfer_resources", "Transfer conserved food or energy between regions. Enforces donor needs, recipient shortage, shared transport budget and current revision.",
                        Map.of("from", "string", "to", "string", "resource", "string", "amount", "integer", "revision", "integer")));
    }
    @Override public synchronized ActionResult validate(AgentAction action) {
        var schema = actions().stream().filter(s -> s.name().equals(action.name())).findFirst();
        if (schema.isEmpty()) return ActionResult.failure("Unsupported action: " + action.name());
        var error = schema.get().validate(action.parameters());
        if (error.isPresent()) return ActionResult.failure(error.get());
        if (action.name().equals("transfer_resources")) {
            var p = action.parameters(); String resource = (String) p.get("resource");
            Region from = regions.get(p.get("from")), to = regions.get(p.get("to"));
            double amount = ((Number) p.get("amount")).doubleValue();
            if (from == null || to == null || p.get("from").equals(p.get("to")) || !Set.of("food", "energy").contains(resource)) return ActionResult.failure("Select distinct existing regions and food or energy");
            if (((Number) p.get("revision")).doubleValue() != revision) return ActionResult.failure("Stale regional plan: recalculate options");
            if (amount <= 0 || amount > transportRemaining || amount > from.reserve(resource) - from.need(resource)
                    || amount > to.need(resource) - to.reserve(resource)) return ActionResult.failure("Infeasible transfer: positive amount, donor reserve floor, recipient need and transport capacity must all hold");
        }
        if (action.name().equals("adjust_resources")) {
            double next = ((Number) facts.get("resourceLevel")).doubleValue()
                    + ((Number) action.parameters().get("amount")).doubleValue();
            if (next < 0 || next > 100) return ActionResult.failure("Resource level must remain within 0..100");
        }
        return ActionResult.success("Valid");
    }
    @Override public synchronized ActionResult execute(AgentAction action) {
        var validation = validate(action);
        if (!validation.success()) return validation;
        switch (action.name()) {
            case "transfer_resources" -> {
                var p = action.parameters(); String from = (String) p.get("from"), to = (String) p.get("to"), resource = (String) p.get("resource");
                int amount = ((Number) p.get("amount")).intValue();
                regions.put(from, regions.get(from).change(resource, -amount)); regions.put(to, regions.get(to).change(resource, amount));
                transportRemaining -= amount; revision++;
                return new ActionResult(true, "Transferred " + amount + " " + resource + " from " + from + " to " + to,
                        Map.of("from", regions.get(from).attributes(), "to", regions.get(to).attributes(), "transportRemaining", transportRemaining, "revision", revision));
            }
            case "adjust_resources" -> facts.put("resourceLevel", ((Number) facts.get("resourceLevel")).intValue()
                    + ((Number) action.parameters().get("amount")).intValue());
            case "set_alert" -> facts.put("alert", action.parameters().get("alert"));
            default -> throw new IllegalStateException("Validated unknown action");
        }
        return new ActionResult(true, "Applied " + action.name() + ": " + facts, Map.copyOf(facts));
    }
    @Override public synchronized void advanceTick() {
        facts.put("simulationTick", ((Number) facts.get("simulationTick")).longValue() + 1);
    }
}
