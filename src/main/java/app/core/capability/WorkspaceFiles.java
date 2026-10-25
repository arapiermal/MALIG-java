package app.core.capability;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Restricted text-file capabilities. The configured root is trusted host configuration, never a tool argument. */
public final class WorkspaceFiles {
    public static final String ROOT_PROPERTY = "hmas.workspace.root";
    public static final int READ_LIMIT = 32 * 1024, WRITE_LIMIT = 64 * 1024;
    public static final int LIST_LIMIT = 100, SCAN_LIMIT = 256, DEPTH_LIMIT = 4;
    public static final Set<String> NAMES = Set.of("list_workspace_files", "read_text_file", "write_text_file");
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "json", "csv");
    private final Path root;

    public static Path defaultRoot() {
        String value = System.getProperty(ROOT_PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv("HMAS_WORKSPACE_ROOT");
        return Path.of(value == null || value.isBlank() ? "hmas-workspace" : value);
    }
    public WorkspaceFiles(Path root) { this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); }
    public List<Capability> capabilities() {
        var writeSchema = ToolSchema.of("write_text_file", "Create a new UTF-8 .txt/.md/.json/.csv file in the restricted workspace. Never overwrites; existing parent folders only. Maximum 65536 encoded bytes.", Map.of("path", "string", "content", "string"));
        var parameters = new HashMap<>(writeSchema.parametersSchema());
        var properties = new HashMap<>((Map<String, Object>) parameters.get("properties"));
        properties.put("content", Map.of("type", "string", "minLength", 0)); parameters.put("properties", properties);
        return List.of(
                new ToolCapability(ToolSchema.of("list_workspace_files", "List supported regular text files under the workspace (100 files, 256 scanned entries, 4 directory levels maximum; reports truncation). Never follows links.", Map.of()), (id, call) -> execute("list_workspace_files", call.arguments())),
                new ToolCapability(ToolSchema.of("read_text_file", "Read a workspace-relative UTF-8 .txt/.md/.json/.csv file, maximum 32768 bytes. Absolute paths, traversal and links are forbidden.", Map.of("path", "string")), (id, call) -> execute("read_text_file", call.arguments())),
                new ToolCapability(new ToolSchema(writeSchema.name(), writeSchema.description(), parameters), (id, call) -> execute("write_text_file", call.arguments())));
    }
    private static final class Rejected extends IOException { Rejected(String message) { super(message); } }
    @FunctionalInterface private interface Operation { ToolResult run() throws IOException; }
    private ToolResult guarded(Operation operation) {
        try { return operation.run(); }
        catch (Rejected ex) { return ToolResult.failure(ex.getMessage()); }
        catch (FileAlreadyExistsException ex) { return ToolResult.failure("File already exists; overwriting is forbidden"); }
        catch (NoSuchFileException ex) { return ToolResult.failure("File or parent directory does not exist"); }
        catch (AccessDeniedException ex) { return ToolResult.failure("Filesystem access denied"); }
        catch (CharacterCodingException ex) { return ToolResult.failure("Content must be valid UTF-8 text (no malformed Unicode)"); }
        catch (IOException | SecurityException | UnsupportedOperationException ex) { return ToolResult.failure("Workspace operation failed; check permissions and unsupported links/file types. No host paths are disclosed"); }
    }
    private synchronized ToolResult execute(String operation, Map<String, Object> args) {
        return guarded(() -> {
            // Reject paths/oversized writes before creating the workspace or opening a file.
            Path relative = operation.equals("list_workspace_files") ? null : relative((String) args.get("path"));
            ByteBuffer content = null;
            if (operation.equals("write_text_file")) {
                String text = (String) args.get("content");
                if (text.length() > WRITE_LIMIT) throw new Rejected("Write exceeds 64 KiB UTF-8 limit");
                content = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
                if (content.remaining() > WRITE_LIMIT) throw new Rejected("Write exceeds 64 KiB UTF-8 limit");
            }
            ensureRoot();
            try (Directory directory = openRoot()) {
                ToolResult result;
                if (relative == null) {
                    Listing listing = new Listing(); list(directory, "", 0, listing);
                    listing.files.sort(Comparator.comparing(f -> (String) f.get("path")));
                    result = ToolResult.success("Listed " + listing.files.size() + " supported files" + (listing.truncated ? " (bounded listing; truncated)" : ""),
                            Map.of("files", listing.files, "truncated", listing.truncated, "scannedEntries", listing.scanned, "maxFiles", LIST_LIMIT, "maxDepth", DEPTH_LIMIT));
                } else result = withParent(directory, relative, 0, content);
                directory.verify(); return result;
            }
        });
    }
    private Path relative(String text) throws Rejected {
        if (text == null || text.isBlank() || text.length() > 240) throw new Rejected("Provide a relative file path of at most 240 characters");
        String portable = text.replace('\\', '/');
        if (portable.startsWith("/")) throw new Rejected("Absolute paths are forbidden");
        String[] parts = portable.split("/", -1);
        for (String part : parts) {
            if (part.equals("..") || part.equals(".")) throw new Rejected("Path traversal/dot components are forbidden");
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,119}") || part.endsWith(".") || part.endsWith(" ")
                    || part.split("\\.", 2)[0].matches("(?i:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])"))
                throw new Rejected("Unsupported path component; use ordinary relative file names, no drive/UNC/device paths or streams");
        }
        if (!supported(parts[parts.length - 1])) throw new Rejected("Unsupported extension; allowed: .txt, .md, .json, .csv");
        Path path = root.getFileSystem().getPath(portable);
        if (path.isAbsolute() || !root.resolve(path).normalize().startsWith(root)) throw new Rejected("Path escapes workspace");
        return path;
    }
    private static boolean supported(String name) { int dot = name.lastIndexOf('.'); return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)); }
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
    private static void checkDirectory(Path path) throws IOException {
        var a = attributes(path);
        if (!a.isDirectory() || a.isSymbolicLink() || a.isOther() || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new Rejected("Symbolic links, redirected directories and special files are forbidden");
    }
    private void ensureRoot() throws IOException {
        Path cursor = root.getRoot();
        for (Path part : root) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(cursor); } catch (FileAlreadyExistsException ignored) { /* checked below */ }
            }
            checkDirectory(cursor);
        }
    }
    private void verifyRoot() throws IOException {
        Path cursor = root.getRoot(); for (Path part : root) { cursor = cursor.resolve(part); checkDirectory(cursor); }
    }
    private final class Directory implements AutoCloseable {
        final Path path; final DirectoryStream<Path> stream; final SecureDirectoryStream<Path> secure; final Object key;
        Directory(Path path, DirectoryStream<Path> stream, Object expectedKey) throws IOException {
            this.path = path; this.stream = stream;
            secure = stream instanceof SecureDirectoryStream<Path> s ? s : null;
            key = expectedKey;
            try { verify(); } catch (IOException ex) { stream.close(); throw ex; }
        }
        void verify() throws IOException {
            BasicFileAttributes actual;
            if (secure != null) actual = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
            else { verifyRoot(); checkDirectory(path); actual = attributes(path); }
            if (key != null && !key.equals(actual.fileKey())) throw new Rejected("Workspace directory changed during operation");
        }
        BasicFileAttributes stat(Path name) throws IOException {
            verify(); return secure == null ? attributes(path.resolve(name))
                    : secure.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        }
        Directory child(Path name) throws IOException {
            var a = stat(name); if (!a.isDirectory() || a.isSymbolicLink() || a.isOther()) throw new Rejected("Symbolic links and special entries are forbidden");
            if (secure != null) return new Directory(path.resolve(name), secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS), a.fileKey());
            checkDirectory(path.resolve(name)); return new Directory(path.resolve(name), Files.newDirectoryStream(path.resolve(name)), a.fileKey());
        }
        SeekableByteChannel channel(Path name, Set<OpenOption> options) throws IOException {
            verify(); return secure == null ? Files.newByteChannel(path.resolve(name), options) : secure.newByteChannel(name, options);
        }
        @Override public void close() throws IOException { stream.close(); }
    }
    private Directory openRoot() throws IOException {
        verifyRoot(); var a = attributes(root); return new Directory(root, Files.newDirectoryStream(root), a.fileKey());
    }
    private ToolResult withParent(Directory directory, Path path, int index, ByteBuffer content) throws IOException {
        if (index < path.getNameCount() - 1) {
            try (Directory child = directory.child(path.getName(index))) {
                ToolResult result = withParent(child, path, index + 1, content); directory.verify(); return result;
            }
        }
        Path name = path.getFileName(); String display = path.toString().replace('\\', '/');
        if (content != null) {
            // CREATE_NEW atomically refuses existing targets (including symlinks). Parent folders must already exist.
            int bytes = content.remaining();
            try (SeekableByteChannel file = directory.channel(name, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
                while (content.hasRemaining()) file.write(content);
            }
            directory.verify();
            return ToolResult.success("Created new workspace file", Map.of("path", display, "bytesWritten", bytes, "created", true));
        }
        var a = directory.stat(name);
        if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) throw new Rejected("Only regular non-link text files can be read");
        if (a.size() > READ_LIMIT) throw new Rejected("Read exceeds 32 KiB limit");
        ByteBuffer bytes = ByteBuffer.allocate(READ_LIMIT + 1);
        try (SeekableByteChannel file = directory.channel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (file.size() > READ_LIMIT) throw new Rejected("Read exceeds 32 KiB limit");
            while (bytes.hasRemaining() && file.read(bytes) != -1) { }
        }
        if (bytes.position() > READ_LIMIT) throw new Rejected("Read exceeds 32 KiB limit");
        int count = bytes.position(); bytes.flip();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(bytes).toString();
        directory.verify();
        return ToolResult.success("Read workspace text file", Map.of("path", display, "bytesRead", count, "content", text, "encoding", "UTF-8"));
    }
    private static final class Listing { final List<Map<String, Object>> files = new ArrayList<>(); int scanned; boolean truncated; }
    private void list(Directory directory, String prefix, int depth, Listing listing) throws IOException {
        for (Path entry : directory.stream) {
            if (listing.scanned >= SCAN_LIMIT || listing.files.size() >= LIST_LIMIT) { listing.truncated = true; break; }
            listing.scanned++; Path name = entry.getFileName(); var a = directory.stat(name);
            if (a.isSymbolicLink() || a.isOther()) throw new Rejected("Listing refused: symbolic links or special entries in workspace");
            String display = prefix + name;
            if (a.isDirectory()) {
                if (depth >= DEPTH_LIMIT) { listing.truncated = true; continue; }
                try (Directory child = directory.child(name)) { list(child, display + "/", depth + 1, listing); }
            } else if (a.isRegularFile() && supported(name.toString())) {
                try { relative(display); } catch (Rejected ex) { continue; }
                listing.files.add(Map.of("path", display, "bytes", a.size()));
            }
        }
        directory.verify();
    }
}
