package io.kcg.sir.application.internal;

import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Publishes original source bytes, not GeneratedFiles. No partial candidate becomes visible on failure. */
public final class SourceCandidatePublisher {
    private SourceCandidatePublisher() {}

    public static void publish(Path root, SourceSnapshot sources) throws IOException {
        publish(root, sources, stage -> {});
    }

    /** Test seam: simulate a write failure or a conflicting destination before publication. */
    static void publish(Path root, SourceSnapshot sources, BeforePublish hook) throws IOException {
        if (!root.isAbsolute() || !root.equals(root.normalize()) || root.getParent() == null)
            throw new IOException("candidate root must be an absolute normalized path with a parent");
        long total = 0;
        for (var file : sources.manifest().files()) {
            String issue = PathGuard.validateRelativePath(file.sourceId().value());
            total += file.byteCount();
            if (issue != null) throw new IOException("illegal candidate source path: " + issue);
            if (file.byteCount() > ProjectSourceReader.MAX_FILE_BYTES || total > ProjectSourceReader.MAX_TOTAL_BYTES)
                throw new IOException("candidate exceeds the 1 MiB/file or 8 MiB/source-set budget");
        }
        List<Path> createdParents = new ArrayList<>();
        Path stage = null;
        try {
            ensureParents(root.getParent(), createdParents);
            safeChain(root.getParent());
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("candidate root already exists");
            stage = Files.createTempDirectory(root.getParent(), ".sir-candidate-");
            for (var file : sources.manifest().files()) {
                Path path = stage.resolve(file.sourceId().value());
                Files.createDirectories(path.getParent());
                Files.write(path, sources.bytes(file.sourceId()));
            }
            hook.run(stage);
            safeChain(root.getParent());
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("candidate root appeared before publication");
            Files.move(stage, root, StandardCopyOption.ATOMIC_MOVE);
            stage = null;
        } finally {
            if (stage != null) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                for (int i = createdParents.size() - 1; i >= 0; i--) {
                    try { Files.deleteIfExists(createdParents.get(i)); }
                    catch (IOException ignored) { /* Never remove a nonempty directory or somebody else's content. */ }
                }
            }
        }
    }

    private static void ensureParents(Path parent, List<Path> created) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path existing = parent;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(existing); existing = existing.getParent();
        }
        safeChain(existing);
        for (int i = missing.size() - 1; i >= 0; i--) {
            Files.createDirectory(missing.get(i));
            created.add(missing.get(i));
        }
    }

    private static void safeChain(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) {
            var attributes = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("candidate parent chain must contain only real directories");
        }
    }

    @FunctionalInterface interface BeforePublish { void run(Path stage) throws IOException; }
}
