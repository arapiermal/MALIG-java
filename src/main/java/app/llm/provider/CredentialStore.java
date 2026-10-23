package app.llm.provider;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** Development credentials: session > process environment > local .env. Never logs secrets. */
public final class CredentialStore {
    private static final CredentialStore DEFAULT = new CredentialStore(Path.of(".env"), System::getenv);
    private static final Pattern ENTRY = Pattern.compile("^\\s*(?:export\\s+)?(GOOGLE_API_KEY|GEMINI_API_KEY|OPENAI_API_KEY|GROQ_API_KEY|VENICE_API_KEY|CUSTOM_API_KEY)\\s*=(.*)$");
    private final Path envFile;
    private final Function<String, String> environment;
    private final Map<String, String> sessionKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private static List<String> names(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "google" -> List.of("GOOGLE_API_KEY", "GEMINI_API_KEY");
            case "openai" -> List.of("OPENAI_API_KEY");
            case "groq" -> List.of("GROQ_API_KEY");
            case "venice" -> List.of("VENICE_API_KEY");
            case "custom" -> List.of("CUSTOM_API_KEY");
            default -> throw new IllegalArgumentException("No credential profile for selected provider");
        };
    }
    public record Credential(String key, String source) {
        @Override public String toString() { return source + " (key masked; not validated)"; }
    }
    public CredentialStore(Path envFile, Function<String, String> environment) {
        this.envFile = envFile.toAbsolutePath().normalize(); this.environment = environment;
    }
    public static CredentialStore current() { return DEFAULT; }
    public static void setGoogleKey(String key) { DEFAULT.setSessionKey(key); }
    public static String googleKey() { return DEFAULT.resolve().map(Credential::key).orElse(null); }
    public Path envFile() { return envFile; }
    public void setSessionKey(String key) { setSessionKey("google", key); }
    public synchronized void setSessionKey(String provider, String key) {
        String name = names(provider).getFirst();
        if (key == null || key.isBlank()) sessionKeys.remove(name); else sessionKeys.put(name, validKey(key.strip()));
    }
    public Optional<Credential> resolve() { return resolve("google"); }
    public Optional<Credential> resolve(String provider) {
        List<String> aliases = names(provider);
        String session = sessionKeys.get(aliases.getFirst());
        if (session != null) return Optional.of(new Credential(session, "session"));
        for (String name : aliases) {
            String value = environment.apply(name);
            if (value != null && !value.isBlank()) return Optional.of(new Credential(validKey(value.strip()), "process environment: " + name));
        }
        Map<String, String> local = new LinkedHashMap<>();
        for (String line : readLocal().split("\\R")) {
            Matcher matcher = ENTRY.matcher(line);
            if (matcher.matches() && aliases.contains(matcher.group(1))) local.put(matcher.group(1), decode(matcher.group(2)));
        }
        for (String name : aliases) {
            String value = local.get(name);
            if (value != null && !value.isBlank()) return Optional.of(new Credential(validKey(value), ".env: " + name));
        }
        return Optional.empty();
    }
    public String status() { return status("google"); }
    public String status(String provider) {
        return resolve(provider).map(Credential::toString).orElse("No credential configured for " + provider) +
                (Files.exists(envFile) ? " | local .env detected" : " | no local .env");
    }
    private String readLocal() {
        try { return Files.exists(envFile) ? Files.readString(envFile, StandardCharsets.UTF_8) : ""; }
        catch (IOException ex) { throw new IllegalStateException("Cannot read local .env; check file permissions"); }
    }
    private static String decode(String text) {
        String value = text.strip();
        if (value.startsWith("\"") || value.startsWith("'")) {
            int end = value.indexOf(value.charAt(0), 1);
            if (end < 0) throw new IllegalArgumentException("Unclosed quote in API-key .env entry");
            return value.substring(1, end).strip();
        }
        return value.split("\\s+#", 2)[0].strip();
    }
    private static String validKey(String key) {
        if (!key.matches("[A-Za-z0-9._~+/-]+=*")) throw new IllegalArgumentException("Credential must be a single API-key token; no quotes, spaces or newlines");
        return key;
    }
    /** Update/remove both aliases, preserving unrelated entries/comments. No OS environment modification. */
    public void writeLocalKey(String key) { writeLocalKey("google", key); }
    public synchronized void writeLocalKey(String provider, String key) {
        List<String> aliases = names(provider);
        if (key != null) key = validKey(key.strip());
        if (Files.isSymbolicLink(envFile)) throw new IllegalStateException("Refusing to replace a symlinked .env");
        String original = readLocal(), newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(original.split("\\r?\\n", -1)));
        List<String> updated = new ArrayList<>();
        boolean inserted = false;
        for (String line : lines) {
            Matcher entry = ENTRY.matcher(line);
            if (entry.matches() && aliases.contains(entry.group(1))) {
                if (!inserted && key != null) { updated.add(aliases.getFirst() + "=" + key); inserted = true; }
            } else updated.add(line);
        }
        if (key != null && !inserted) {
            if (!updated.isEmpty() && updated.getLast().isEmpty()) updated.removeLast();
            updated.add(aliases.getFirst() + "=" + key); updated.add("");
        }
        String content = String.join(newline, updated);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(envFile.getParent(), ".env-", ".tmp");
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            if (!readLocal().equals(original)) throw new IllegalStateException(".env changed during editing; reload and retry");
            try { Files.move(temporary, envFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, envFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { throw new IllegalStateException("Cannot update local .env; check file permissions"); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
