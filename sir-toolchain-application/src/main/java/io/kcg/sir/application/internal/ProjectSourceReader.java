package io.kcg.sir.application.internal;

import io.kcg.sir.api.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.semantic.api.ProjectSemanticInput;
import io.kcg.sir.source.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Reads only the explicit list, through directory handles; never follows a source symlink. */
public final class ProjectSourceReader {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    private ProjectSourceReader() {}

    public record Loaded(SourceSnapshot snapshot, ProjectSemanticInput semanticInput) {}

    public static Optional<Loaded> read(ProjectToolchainRequest request, List<ExecutionDiagnostic> diagnostics) {
        var parser = SirParser.create();
        SourceSpan active = zero(request.entry());
        try {
            validateRoot(request.sourceRoot());
            validateLogicalPath(request.entry().value());
            try (var opened = openRoot(request.sourceRoot())) {
                var secure = opened.directory();
                var read = readFile(secure, request.entry());
                var rootResult = parser.parseProject(new SirSource(request.entry(), SourceReader.decodeStrictUtf8(read.bytes())));
                diagnostics.addAll(DiagnosticMapper.fromProjectSource(rootResult.diagnostics(), ExecutionStage.PARSE));
                if (!rootResult.isSuccess()) return Optional.empty();
                var root = (AstSourceUnit.Root) rootResult.unit().orElseThrow();
                if (root.sources().size() + 1 > SourceSetManifest.MAX_FILES)
                    throw new ReadFailure("SIR-APP-SOURCES-LIMIT-001", "source list exceeds 128 files including entry");
                var locations = new LinkedHashMap<SourceId, SourceSpan>();
                Set<String> folded = new HashSet<>(); folded.add(request.entry().value().toLowerCase(Locale.ROOT));
                for (var listed : root.sources()) {
                    active = listed.span();
                    validateLogicalPath(listed.path());
                    var id = SourceSetManifest.strictId(listed.path());
                    if (!folded.add(id.value().toLowerCase(Locale.ROOT)))
                        throw new ReadFailure("SIR-APP-SOURCES-PATH-002", "duplicate, entry or case-colliding source: " + id.value());
                    locations.put(id, listed.span());
                }
                Map<SourceId, byte[]> bytes = new LinkedHashMap<>(); bytes.put(request.entry(), read.bytes());
                Set<Object> identities = new HashSet<>(); identities.add(read.fileKey());
                List<AstSourceUnit.Fragment> fragments = new ArrayList<>();
                int total = read.bytes().length;
                // Parse order never affects semantic ordering or the source-set digest.
                for (var source : locations.entrySet()) {
                    active = source.getValue();
                    var fragmentBytes = readFile(secure, source.getKey());
                    if (!identities.add(fragmentBytes.fileKey()))
                        throw new ReadFailure("SIR-APP-SOURCES-PATH-003", "two logical sources alias the same file: " + source.getKey().value());
                    total += fragmentBytes.bytes().length;
                    if (total > MAX_TOTAL_BYTES) throw new ReadFailure("SIR-APP-SOURCES-LIMIT-001", "source set exceeds 8 MiB");
                    bytes.put(source.getKey(), fragmentBytes.bytes());
                    var parsed = parser.parseFragment(new SirSource(source.getKey(), SourceReader.decodeStrictUtf8(fragmentBytes.bytes())));
                    diagnostics.addAll(DiagnosticMapper.fromProjectSource(parsed.diagnostics(), ExecutionStage.PARSE));
                    if (!parsed.isSuccess()) return Optional.empty();
                    fragments.add((AstSourceUnit.Fragment) parsed.unit().orElseThrow());
                }
                return Optional.of(new Loaded(new SourceSnapshot(request.entry(), bytes), new ProjectSemanticInput(root, fragments)));
            }
        } catch (ReadFailure e) {
            add(diagnostics, e.code, e.getMessage(), active);
        } catch (SourceReader.InvalidUtf8Exception e) {
            add(diagnostics, "SIR-APP-SOURCES-UTF8-001", "source is not valid UTF-8", active);
        } catch (IOException | IllegalArgumentException e) {
            add(diagnostics, "SIR-APP-SOURCES-READ-001", "source read refused: " + e.getMessage(), active);
        }
        return Optional.empty();
    }

    private static void validateRoot(Path root) throws IOException {
        if (!root.isAbsolute() || !root.equals(root.normalize())) throw new ReadFailure("SIR-APP-SOURCES-PATH-001", "sourceRoot must be an absolute normalized directory");
    }

    private record RootDirectory(SecureDirectoryStream<Path> directory, List<SecureDirectoryStream<Path>> handles) implements AutoCloseable {
        @Override public void close() throws IOException {
            IOException error = null;
            for (int i = handles.size() - 1; i >= 0; i--) {
                try { handles.get(i).close(); }
                catch (IOException e) { if (error == null) error = e; else error.addSuppressed(e); }
            }
            if (error != null) throw error;
        }
    }

    private static RootDirectory openRoot(Path root) throws IOException {
        var handles = new ArrayList<SecureDirectoryStream<Path>>();
        var stream = Files.newDirectoryStream(root.getRoot());
        if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
            stream.close();
            throw new ReadFailure("SIR-APP-SOURCES-READ-001", "filesystem does not support secure directory-relative source reads");
        }
        handles.add(secure);
        try {
            // Anchor at the filesystem root, opening EVERY source-root segment without following links.
            for (Path part : root) {
                secure = secure.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                handles.add(secure);
            }
            return new RootDirectory(secure, handles);
        } catch (IOException | RuntimeException e) {
            try { new RootDirectory(secure, handles).close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    private static void validateLogicalPath(String raw) throws ReadFailure {
        try {
            SourceSetManifest.strictId(raw);
            String issue = PathGuard.validateRelativePath(raw);
            if (issue != null) throw new IllegalArgumentException(issue);
            if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > SourceSetManifest.MAX_PATH_BYTES)
                throw new IllegalArgumentException("source path exceeds 512 UTF-8 bytes");
        } catch (IllegalArgumentException e) { throw new ReadFailure("SIR-APP-SOURCES-PATH-001", "illegal source path " + raw + ": " + e.getMessage()); }
    }

    private record ReadBytes(byte[] bytes, Object fileKey) {}
    private static ReadBytes readFile(SecureDirectoryStream<Path> root, SourceId id) throws IOException {
        var handles = new ArrayList<SecureDirectoryStream<Path>>();
        var directory = root;
        String[] parts = id.value().split("/");
        try {
            for (int i = 0; i < parts.length - 1; i++) {
                directory = directory.newDirectoryStream(Path.of(parts[i]), LinkOption.NOFOLLOW_LINKS);
                handles.add(directory);
            }
            Path name = Path.of(parts[parts.length - 1]);
            var view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            var before = view.readAttributes();
            if (!before.isRegularFile() || before.fileKey() == null)
                throw new ReadFailure("SIR-APP-SOURCES-PATH-001", "source must be an identifiable regular file, not a link/device: " + id.value());
            if (before.size() > MAX_FILE_BYTES) throw new ReadFailure("SIR-APP-SOURCES-LIMIT-001", "source exceeds 1 MiB: " + id.value());
            var out = new ByteArrayOutputStream();
            try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                var buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    int size = buffer.position();
                    if (out.size() + size > MAX_FILE_BYTES) throw new ReadFailure("SIR-APP-SOURCES-LIMIT-001", "source exceeds 1 MiB while reading: " + id.value());
                    out.write(buffer.array(), 0, size); buffer.clear();
                }
            }
            var after = view.readAttributes();
            if (!after.isRegularFile() || !before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                    || before.size() != out.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                throw new ReadFailure("SIR-APP-SOURCES-READ-002", "source changed during read: " + id.value());
            return new ReadBytes(out.toByteArray(), before.fileKey());
        } finally {
            for (int i = handles.size() - 1; i >= 0; i--) handles.get(i).close();
        }
    }

    private static SourceSpan zero(SourceId id) { return new SourceSpan(id, new SourcePosition(0, 1, 1), new SourcePosition(0, 1, 1)); }
    private static void add(List<ExecutionDiagnostic> diagnostics, String code, String message, SourceSpan span) {
        diagnostics.add(new ExecutionDiagnostic(code, ExecutionStage.READ, ExecutionSeverity.ERROR, message,
                Optional.of(span), Optional.empty(), Optional.empty()));
    }
    private static final class ReadFailure extends IOException {
        final String code;
        ReadFailure(String code, String message) { super(message); this.code = code; }
    }
}
