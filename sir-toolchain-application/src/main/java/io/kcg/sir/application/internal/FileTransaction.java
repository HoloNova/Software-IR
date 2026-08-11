package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FailureDisposition;
import io.kcg.sir.application.api.FileAction;
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
 * Same-volume staged file transaction. A new output root is published by one
 * atomic tree move. An existing root is updated by atomic per-file moves with
 * backups and compensating rollback.
 */
public final class FileTransaction {

    private final Path outputRoot;
    private final List<GeneratedFile> files;
    private final ConflictPolicy conflictPolicy;
    private final TransactionHooks hooks;

    public FileTransaction(
            Path outputRoot, List<GeneratedFile> files, ConflictPolicy conflictPolicy) {
        this(outputRoot, files, conflictPolicy, TransactionHooks.noOp());
    }

    FileTransaction(
            Path outputRoot,
            List<GeneratedFile> files,
            ConflictPolicy conflictPolicy,
            TransactionHooks hooks) {
        Objects.requireNonNull(outputRoot, "outputRoot");
        if (!outputRoot.isAbsolute()) {
            throw new IllegalArgumentException("outputRoot must be absolute");
        }
        this.outputRoot = outputRoot.normalize();
        this.files = List.copyOf(Objects.requireNonNull(files, "files"));
        this.conflictPolicy = Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    public TransactionResult execute() {
        List<Path> createdAncestorDirs = new ArrayList<>();
        Path stagingDir = null;
        try {
            Path outputParent = outputRoot.getParent();
            if (outputParent == null) {
                return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-001",
                        "output root has no parent directory: " + outputRoot);
            }
            ensureParentDirs(outputParent, createdAncestorDirs);
            assertNoSymlinkInChain(outputParent);
            stagingDir = Files.createTempDirectory(outputParent, ".sir-tx-");

            hooks.beforeStagingWrite(stagingDir);
            List<StagedFile> staged = writeStaging(stagingDir);

            if (!Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
                return publishNewRoot(stagingDir, staged, createdAncestorDirs);
            }
            if (Files.isSymbolicLink(outputRoot)) {
                cleanupStaging(stagingDir);
                cleanupCreatedDirs(createdAncestorDirs);
                return failure(FailureDisposition.NO_CHANGES, "SIR-APP-PATH-003",
                        "output root is a symbolic link: " + outputRoot);
            }
            if (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
                cleanupStaging(stagingDir);
                cleanupCreatedDirs(createdAncestorDirs);
                return failure(FailureDisposition.NO_CHANGES, "SIR-APP-CONFLICT-002",
                        "output root is not a directory: " + outputRoot);
            }
            return publishExistingRoot(stagingDir, staged, createdAncestorDirs);
        } catch (StagingFailure e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-003",
                    "staging failure: " + message(e));
        } catch (AtomicMoveNotSupportedException e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-001",
                    "atomic move not supported: " + message(e));
        } catch (IOException e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-004",
                    "I/O failure during transaction setup: " + message(e));
        } catch (Exception e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-005",
                    "unexpected failure during transaction: " + message(e));
        }
    }

    private TransactionResult publishNewRoot(
            Path stagingDir, List<StagedFile> staged, List<Path> createdAncestorDirs) {
        try {
            assertNoSymlinkInChain(outputRoot.getParent());
            if (Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new CommitConflict("SIR-APP-CONFLICT-001",
                        "output root appeared during commit: " + outputRoot);
            }
            Files.move(stagingDir, outputRoot, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-001",
                    "atomic move not supported for new root: " + message(e));
        } catch (CommitConflict e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, e.code, e.getMessage());
        } catch (IOException e) {
            cleanupStaging(stagingDir);
            cleanupCreatedDirs(createdAncestorDirs);
            return failure(FailureDisposition.NO_CHANGES, "SIR-APP-WRITE-002",
                    "failed to atomically publish new root: " + message(e));
        }

        List<AppliedFile> applied = new ArrayList<>(staged.size());
        for (StagedFile file : staged) {
            applied.add(toAppliedFile(file, FileAction.CREATED));
        }
        return new TransactionResult.Success(outputRoot, applied, List.of());
    }

    /**
    * [RQ-11] Each file is re-validated (assertSafeTarget + conflict policy)
    * immediately before its backup and again before its move: preflight
    * guarantees do not survive into the commit window, so a symlink or new
    * target inserted between checks must fail closed at the point of use.
    * Verified by FileTransactionFaultInjectionTest and the RQ-09 junction
    * review (fileTransactionRejectsJunctionInTargetParentChain).
    */
   private TransactionResult publishExistingRoot(
            Path stagingDir, List<StagedFile> staged, List<Path> createdAncestorDirs) {
        List<CommitEntry> entries = new ArrayList<>();
        List<Path> createdTargetParentDirs = new ArrayList<>();
        Path backupDir = null;
        try {
            backupDir = Files.createTempDirectory(stagingDir.getParent(), ".sir-bak-");
            for (int i = 0; i < staged.size(); i++) {
                StagedFile stagedFile = staged.get(i);
                Path target = outputRoot.resolve(stagedFile.generated.relativePath()).normalize();
                CommitEntry entry = new CommitEntry(stagedFile, target);
                entries.add(entry);

                hooks.beforeCommitFile(i, stagedFile.stagedPath, target);
                assertSafeTarget(target);
                enforceConflictPolicy(target);

                ensureTargetParentDirs(target.getParent(), createdTargetParentDirs);
                assertSafeTarget(target);
                enforceConflictPolicy(target);

                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = Files.createTempFile(backupDir, "bak-", null);
                    Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
                    entry.backup = backup;
                    entry.action = FileAction.REPLACED;
                    hooks.afterBackupBeforeCommit(i, backup, target);
                }

                assertSafeTarget(target);
                if (conflictPolicy == ConflictPolicy.FAIL_IF_EXISTS
                        && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new CommitConflict("SIR-APP-CONFLICT-001",
                            "target appeared during commit: "
                                    + stagedFile.generated.relativePath());
                }
                Files.move(stagedFile.stagedPath, target, StandardCopyOption.ATOMIC_MOVE);
                entry.committed = true;
            }

            List<ExecutionDiagnostic> warnings = new ArrayList<>();
            deleteQuietly(backupDir, warnings, "SIR-APP-CLEANUP-001");
            cleanupStagingQuietly(stagingDir, warnings);

            List<AppliedFile> applied = new ArrayList<>(entries.size());
            for (CommitEntry entry : entries) {
                applied.add(toAppliedFile(entry.stagedFile, entry.action));
            }
            return new TransactionResult.Success(outputRoot, applied, warnings);
        } catch (Exception commitFailure) {
            String code = commitFailure instanceof CommitConflict conflict
                    ? conflict.code : "SIR-APP-WRITE-006";
            boolean changedOutput = entries.stream().anyMatch(CommitEntry::changedOutput)
                    || !createdTargetParentDirs.isEmpty();
            if (!changedOutput) {
                cleanupStaging(stagingDir);
                deleteRecursivelyQuietly(backupDir);
                cleanupCreatedDirs(createdAncestorDirs);
                return failure(FailureDisposition.NO_CHANGES, code,
                        "commit rejected without output changes: " + message(commitFailure));
            }
            return rollback(entries, stagingDir, backupDir, createdTargetParentDirs,
                    createdAncestorDirs, code, commitFailure);
        }
    }

    private void enforceConflictPolicy(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String relativePath = outputRoot.relativize(target).toString();
        if (conflictPolicy == ConflictPolicy.FAIL_IF_EXISTS) {
            throw new CommitConflict("SIR-APP-CONFLICT-001",
                    "target exists at commit time and policy is FAIL_IF_EXISTS: " + relativePath);
        }
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new CommitConflict("SIR-APP-CONFLICT-002",
                    "only an existing regular file may be replaced: " + relativePath);
        }
    }

    private TransactionResult rollback(
            List<CommitEntry> entries,
            Path stagingDir,
            Path backupDir,
            List<Path> createdTargetParentDirs,
            List<Path> createdAncestorDirs,
            String commitCode,
            Exception commitFailure) {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(error(commitCode, "commit failed: " + message(commitFailure)));
        int step = 0;

        for (int i = entries.size() - 1; i >= 0; i--) {
            CommitEntry entry = entries.get(i);
            if (!entry.changedOutput()) {
                continue;
            }
            try {
                hooks.beforeRollbackStep(step++, "restore file " + i);
                if (entry.committed) {
                    Files.deleteIfExists(entry.target);
                }
                if (entry.backup != null) {
                    if (Files.exists(entry.target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException(
                                "cannot restore backup over an unexpected target: " + entry.target);
                    }
                    assertNoSymlinkInChain(entry.target.getParent());
                    Files.move(entry.backup, entry.target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (Exception rollbackFailure) {
                diagnostics.add(rollbackError("SIR-APP-ROLLBACK-001",
                        "rollback failed at file " + i + ": " + message(rollbackFailure)
                                + "; recovery materials retained in " + backupDir));
                return new TransactionResult.Failure(
                        FailureDisposition.RECOVERY_REQUIRED, diagnostics);
            }
        }

        for (int i = createdTargetParentDirs.size() - 1; i >= 0; i--) {
            try {
                hooks.beforeRollbackStep(step++, "delete created directory " + i);
                Files.deleteIfExists(createdTargetParentDirs.get(i));
            } catch (Exception rollbackFailure) {
                diagnostics.add(rollbackError("SIR-APP-ROLLBACK-002",
                        "rollback failed while cleaning directory: " + message(rollbackFailure)
                                + "; recovery materials retained in " + backupDir));
                return new TransactionResult.Failure(
                        FailureDisposition.RECOVERY_REQUIRED, diagnostics);
            }
        }

        try {
            hooks.beforeRollbackStep(step++, "delete staging");
            deleteRecursively(stagingDir);
        } catch (Exception cleanupFailure) {
            diagnostics.add(rollbackWarning("SIR-APP-ROLLBACK-003",
                    "staging cleanup failed: " + message(cleanupFailure)));
        }
        try {
            hooks.beforeRollbackStep(step, "delete backup directory");
            deleteRecursively(backupDir);
        } catch (Exception cleanupFailure) {
            diagnostics.add(rollbackWarning("SIR-APP-ROLLBACK-004",
                    "backup cleanup failed: " + message(cleanupFailure)));
        }
        cleanupCreatedDirs(createdAncestorDirs);
        return new TransactionResult.Failure(FailureDisposition.ROLLED_BACK, diagnostics);
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
                staged.add(new StagedFile(
                        file, stagedPath, bytes.length, Sha256.hexDigest(file.content())));
            }
        } catch (IOException e) {
            throw new StagingFailure(e);
        }
        return staged;
    }

    private void ensureParentDirs(Path directory, List<Path> created) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            assertNoSymlinkInChain(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("output parent is not a directory: " + directory);
            }
            return;
        }
        List<Path> missing = missingPaths(directory, null);
        Path existing = directory;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            assertNoSymlinkInChain(existing);
        }
        createMissingDirectories(missing, created);
    }

    private void ensureTargetParentDirs(Path directory, List<Path> created) throws IOException {
        assertNoSymlinkInChain(directory);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("target parent is not a directory: " + directory);
            }
            return;
        }
        createMissingDirectories(missingPaths(directory, outputRoot), created);
    }

    private List<Path> missingPaths(Path directory, Path stopExclusive) {
        List<Path> missing = new ArrayList<>();
        Path current = directory;
        while (current != null && !current.equals(stopExclusive)
                && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current);
            current = current.getParent();
        }
        return missing;
    }

    private void createMissingDirectories(List<Path> missing, List<Path> created) throws IOException {
        for (int i = missing.size() - 1; i >= 0; i--) {
            Path path = missing.get(i);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(path)
                        || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("unsafe directory appeared during creation: " + path);
                }
            } else {
                Files.createDirectory(path);
                created.add(path);
            }
            assertNoSymlinkInChain(path);
        }
    }

    private void assertSafeTarget(Path target) throws IOException {
        if (!target.startsWith(outputRoot)) {
            throw new IOException("target escapes output root: " + target);
        }
        assertNoSymlinkInChain(target);
    }

    private void assertNoSymlinkInChain(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && isLinkLike(current)) {
                throw new IOException("symbolic link or reparse point in path chain: " + current);
            }
            current = current.getParent();
        }
    }

    private static boolean isLinkLike(Path path) {
        // [RQ-09] Review finding B2: isSymbolicLink() alone does not report NTFS
        // junctions (JDK reports them as isOther under NOFOLLOW). Fail closed on
        // any link-or-other type in the chain.
        try {
            java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(
                    path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException e) {
            return false; // caller's exists(NOFOLLOW) already handled absence
        }
    }

    private void cleanupStaging(Path stagingDir) {
        deleteRecursivelyQuietly(stagingDir);
    }

    private void cleanupCreatedDirs(List<Path> created) {
        for (int i = created.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(created.get(i));
            } catch (IOException ignored) {
                // Best effort; a non-empty directory is not removed.
            }
        }
    }

    private void cleanupStagingQuietly(
            Path stagingDir, List<ExecutionDiagnostic> warnings) {
        try {
            deleteRecursively(stagingDir);
        } catch (Exception e) {
            warnings.add(warning("SIR-APP-CLEANUP-002",
                    "staging cleanup failed: " + message(e)));
        }
    }

    private void deleteQuietly(
            Path path, List<ExecutionDiagnostic> warnings, String code) {
        try {
            deleteRecursively(path);
        } catch (Exception e) {
            warnings.add(warning(code, "cleanup failed for " + path + ": " + message(e)));
        }
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
            // Best effort only before any output mutation.
        }
    }

    private AppliedFile toAppliedFile(StagedFile file, FileAction action) {
        return new AppliedFile(
                file.generated.relativePath(), action, file.byteCount, file.sha256Hex,
                file.generated.artifactId(), file.generated.symbolId());
    }

    private static ExecutionDiagnostic error(String code, String message) {
        return diagnostic(code, ExecutionStage.WRITE, ExecutionSeverity.ERROR, message);
    }

    private static ExecutionDiagnostic warning(String code, String message) {
        return diagnostic(code, ExecutionStage.WRITE, ExecutionSeverity.WARNING, message);
    }

    private static ExecutionDiagnostic rollbackError(String code, String message) {
        return diagnostic(code, ExecutionStage.ROLLBACK, ExecutionSeverity.ERROR, message);
    }

    private static ExecutionDiagnostic rollbackWarning(String code, String message) {
        return diagnostic(code, ExecutionStage.ROLLBACK, ExecutionSeverity.WARNING, message);
    }

    private static ExecutionDiagnostic diagnostic(
            String code, ExecutionStage stage, ExecutionSeverity severity, String message) {
        return new ExecutionDiagnostic(code, stage, severity, message,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static TransactionResult.Failure failure(
            FailureDisposition disposition, String code, String message) {
        return new TransactionResult.Failure(disposition, List.of(error(code, message)));
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static final class StagedFile {
        private final GeneratedFile generated;
        private final Path stagedPath;
        private final long byteCount;
        private final String sha256Hex;

        private StagedFile(
                GeneratedFile generated, Path stagedPath, long byteCount, String sha256Hex) {
            this.generated = generated;
            this.stagedPath = stagedPath;
            this.byteCount = byteCount;
            this.sha256Hex = sha256Hex;
        }
    }

    private static final class CommitEntry {
        private final StagedFile stagedFile;
        private final Path target;
        private FileAction action = FileAction.CREATED;
        private Path backup;
        private boolean committed;

        private CommitEntry(StagedFile stagedFile, Path target) {
            this.stagedFile = stagedFile;
            this.target = target;
        }

        private boolean changedOutput() {
            return backup != null || committed;
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
