package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes a generated project into an output root that does not exist yet.
 *
 * <p>Why it exists: first generation must never leave a half-written project. Every file is written into a
 * staging directory beside the output root first, and the finished tree is published with one atomic move, so
 * the output root either holds the whole project or does not exist. A failure at any point removes the staging
 * directory and any parent directory this call created, and touches nothing else. An output root that already
 * exists is refused: changing an existing project is the job of the version plan and apply, which have their
 * own record of what they did.
 */
public final class FileTransaction {

    private final Path outputRoot;
    private final List<GeneratedFile> files;
    private final TransactionHooks hooks;

    public FileTransaction(Path outputRoot, List<GeneratedFile> files) {
        this(outputRoot, files, TransactionHooks.noOp());
    }

    FileTransaction(Path outputRoot, List<GeneratedFile> files, TransactionHooks hooks) {
        Objects.requireNonNull(outputRoot, "outputRoot");
        if (!outputRoot.isAbsolute()) {
            throw new IllegalArgumentException("outputRoot must be absolute");
        }
        this.outputRoot = outputRoot.normalize();
        this.files = List.copyOf(Objects.requireNonNull(files, "files"));
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    public TransactionResult execute() {
        List<Path> createdAncestorDirs = new ArrayList<>();
        Path stagingDir = null;
        try {
            Path outputParent = outputRoot.getParent();
            if (outputParent == null) {
                return failure("SIR-APP-WRITE-001", "output root has no parent directory: " + outputRoot);
            }
            ensureParentDirs(outputParent, createdAncestorDirs);
            assertNoSymlinkInChain(outputParent);
            stagingDir = Files.createTempDirectory(outputParent, ".sir-tx-");

            hooks.beforeStagingWrite(stagingDir);
            List<StagedFile> staged = writeStaging(stagingDir);
            return publish(stagingDir, staged, createdAncestorDirs);
        } catch (StagingFailure e) {
            return abandon(stagingDir, createdAncestorDirs, "SIR-APP-WRITE-003", "staging failure: " + message(e));
        } catch (AtomicMoveNotSupportedException e) {
            return abandon(stagingDir, createdAncestorDirs, "SIR-APP-WRITE-001", "atomic move not supported: " + message(e));
        } catch (IOException e) {
            return abandon(stagingDir, createdAncestorDirs, "SIR-APP-WRITE-004", "I/O failure during transaction setup: " + message(e));
        } catch (Exception e) {
            return abandon(stagingDir, createdAncestorDirs, "SIR-APP-WRITE-005", "unexpected failure during transaction: " + message(e));
        }
    }

    private TransactionResult publish(Path stagingDir, List<StagedFile> staged, List<Path> createdAncestorDirs) {
        try {
            assertNoSymlinkInChain(outputRoot.getParent());
            if (Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new CommitConflict("SIR-APP-CONFLICT-001", "output root already exists: " + outputRoot);
            }
            Files.move(stagingDir, outputRoot, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            return abandon(stagingDir, createdAncestorDirs, "SIR-APP-WRITE-001", "atomic move not supported for the output root: " + message(e));
        } catch (CommitConflict e) {
            return abandon(stagingDir, createdAncestorDirs, e.code, e.getMessage());
        } catch (IOException e) {
            return abandon(stagingDir, createdAncestorDirs, "SIR-APP-WRITE-002", "failed to atomically publish the output root: " + message(e));
        }

        List<AppliedFile> applied = new ArrayList<>(staged.size());
        for (StagedFile file : staged) {
            applied.add(new AppliedFile(file.generated.relativePath(), file.byteCount, file.sha256Hex,
                    file.generated.artifactId(), file.generated.symbolId()));
        }
        return new TransactionResult.Success(outputRoot, applied, List.of());
    }

    private List<StagedFile> writeStaging(Path stagingDir) throws StagingFailure {
        List<StagedFile> staged = new ArrayList<>(files.size());
        try {
            for (GeneratedFile file : files) {
                Path stagedPath = stagingDir.resolve(file.relativePath()).normalize();
                if (!stagedPath.startsWith(stagingDir)) {
                    throw new IOException("staging path escapes staging root: " + file.relativePath());
                }
                Files.createDirectories(stagedPath.getParent());
                byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
                Files.write(stagedPath, bytes);
                staged.add(new StagedFile(file, bytes.length, Sha256.hexDigest(file.content())));
            }
        } catch (IOException e) {
            throw new StagingFailure(e);
        }
        return staged;
    }

    /** Creates the missing parents of the output root, remembering which ones this call made. */
    private void ensureParentDirs(Path directory, List<Path> created) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            assertNoSymlinkInChain(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("output parent is not a directory: " + directory);
            }
            return;
        }
        List<Path> missing = new ArrayList<>();
        Path current = directory;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current);
            current = current.getParent();
        }
        if (current != null) {
            assertNoSymlinkInChain(current);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            Path path = missing.get(i);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("unsafe directory appeared during creation: " + path);
                }
            } else {
                Files.createDirectory(path);
                created.add(path);
            }
            assertNoSymlinkInChain(path);
        }
    }

    private void assertNoSymlinkInChain(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && isLinkLike(current)) {
                throw new IOException("symbolic link or reparse point in path chain: " + current);
            }
            current = current.getParent();
        }
    }

    private static boolean isLinkLike(Path path) {
        // isSymbolicLink() alone does not report NTFS junctions (the JDK reports them as isOther under NOFOLLOW),
        // so any link-or-other type in the chain is refused.
        try {
            java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(
                    path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException e) {
            return false; // the caller's exists(NOFOLLOW) already handled absence
        }
    }

    /** Removes what this call created and reports why it stopped. */
    private TransactionResult abandon(Path stagingDir, List<Path> createdAncestorDirs, String code, String message) {
        deleteRecursivelyQuietly(stagingDir);
        for (int i = createdAncestorDirs.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(createdAncestorDirs.get(i));
            } catch (IOException ignored) {
                // Best effort; a non-empty directory is not removed.
            }
        }
        return failure(code, message);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private void deleteRecursivelyQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (Exception ignored) {
            // Best effort: nothing has been published yet.
        }
    }

    private static TransactionResult.Failure failure(String code, String message) {
        return new TransactionResult.Failure(List.of(new ExecutionDiagnostic(code, ExecutionStage.WRITE, ExecutionSeverity.ERROR, message,
                Optional.empty(), Optional.empty(), Optional.empty())));
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static final class StagedFile {
        private final GeneratedFile generated;
        private final long byteCount;
        private final String sha256Hex;

        private StagedFile(GeneratedFile generated, long byteCount, String sha256Hex) {
            this.generated = generated;
            this.byteCount = byteCount;
            this.sha256Hex = sha256Hex;
        }
    }

    private static final class StagingFailure extends Exception {
        private StagingFailure(IOException cause) {
            super(cause);
        }
    }

    private static final class CommitConflict extends IOException {
        private final String code;

        private CommitConflict(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
