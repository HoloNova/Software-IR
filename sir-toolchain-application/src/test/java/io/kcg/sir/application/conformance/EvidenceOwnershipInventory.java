package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Append-only ownership inventory tracking the owned-file lifecycle:
 *
 * <pre>
 * ABSENT_PROVED -> PENDING_CREATED -> FINALIZED_EVIDENCE
 * ABSENT_PROVED -> PENDING_CREATED(REPORT_TEMP) -> FINALIZED_REPORT_TEMP
 *               -> REPORT_UNSEALED -> REPORT_SEALED
 * PENDING_CREATED | FINALIZED_* | REPORT_UNSEALED -> DELETE_INTENT -> ABSENT_PROVED_AFTER_DELETE
 * </pre>
 *
 * <p>P0-3 搂3.1: {@link PendingCreation} records the strict path, NOFOLLOW
 * regular-file type, non-reparse status, and non-null strong identity
 * captured at {@code CREATE_NEW} time. It only proves "this run created
 * this file object", NOT the final content.
 *
 * <p>{@link FinalizedEntry} records the byteCount, SHA-256, and strong
 * identity captured after successful flush/close. size/mtime are kept as
 * diagnostic-only fields and never participate in identity authorization.
 *
 * <p>Strong identity is provided by {@link StrongFileIdentity}. There is
 * NO fallback to size, mtime, digest, or path equality. If strong identity
 * is unavailable, all proof fails-closed.
 */
final class EvidenceOwnershipInventory {

    private final Path evidenceRoot;
    private final EvidenceRootProof rootProof;
    private final Map<Path, OwnedFile> files = new LinkedHashMap<>();

    EvidenceOwnershipInventory(Path evidenceRoot, EvidenceRootProof rootProof) {
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot, "evidenceRoot")
                .toAbsolutePath().normalize();
        this.rootProof = rootProof;
    }

    /**
     * Record a pending creation: the file was just created via CREATE_NEW
     * and has not yet been finalized. Only the creation-time strong
     * identity is captured.
     *
     * @param absolutePath the file path
     * @param usage        the file usage category
     * @return the pending creation record
     * @throws IOException if the file is not a regular file or strong
     *                     identity is unavailable
     */
    synchronized PendingCreation recordPending(Path absolutePath, FileUsage usage)
            throws IOException {
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(usage, "usage");
        Path normalized = absolutePath.toAbsolutePath().normalize();
        if (!normalized.startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("file is not under evidence root");
        }
        BasicFileAttributes attrs = readRegularFileAttrs(normalized);
        StrongFileIdentity identity = StrongFileIdentity.of(normalized, attrs);
        PendingCreation pending = new PendingCreation(
                evidenceRoot.relativize(normalized).toString(),
                normalized, identity, usage);
        OwnedFile existing = files.get(normalized);
        if (existing != null) {
            throw new IOException("file already tracked: " + normalized);
        }
        files.put(normalized, new OwnedFile(pending, null, FileState.PENDING_CREATED));
        return pending;
    }

    /**
     * Finalize a pending file: after successful flush/close, capture the
     * final byteCount, SHA-256, and re-verify the strong identity. The
     * file transitions from PENDING_CREATED to FINALIZED_*.
     *
     * @param absolutePath the file path
     * @return the finalized entry
     * @throws IOException if the file is not owned, not pending, identity
     *                     has drifted, or hashing fails
     */
    synchronized FinalizedEntry finalize(Path absolutePath) throws IOException {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        OwnedFile of = files.get(normalized);
        if (of == null) {
            throw new IOException("file is not tracked as owned: " + normalized);
        }
        if (of.state != FileState.PENDING_CREATED) {
            throw new IOException("file is not in PENDING_CREATED state: " + of.state);
        }
        // Re-read attributes and verify identity matches creation.
        BasicFileAttributes attrs = readRegularFileAttrs(normalized);
        StrongFileIdentity nowId = StrongFileIdentity.of(normalized, attrs);
        if (!of.pending.identity().equalsIdentity(nowId)) {
            throw new IOException("IDENTITY_DRIFT_AFTER_CLOSE");
        }
        long byteCount = attrs.size();
        byte[] content = Files.readAllBytes(normalized);
        String sha256 = sha256Hex(content);
        // Verify content length matches.
        if (content.length != byteCount) {
            throw new IOException("BYTE_COUNT_MISMATCH");
        }
        FinalizedEntry finalized = new FinalizedEntry(
                of.pending.canonicalRelativePath(),
                normalized, nowId, byteCount, sha256,
                of.pending.usage());
        FileState finalState = switch (of.pending.usage()) {
            case EVIDENCE -> FileState.FINALIZED_EVIDENCE;
            case REPORT_TEMP -> FileState.FINALIZED_REPORT_TEMP;
            case REPORT_UNSEALED, REPORT_SEALED -> throw new IOException(
                    "unexpected usage for finalize: " + of.pending.usage());
        };
        files.put(normalized, new OwnedFile(of.pending, finalized, finalState));
        return finalized;
    }

    /**
     * Transition a finalized report temp to REPORT_UNSEALED after atomic
     * move. The file's path and strong identity must still match.
     */
    synchronized void markUnsealed(Path absolutePath) throws IOException {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        OwnedFile of = files.get(normalized);
        if (of == null) {
            throw new IOException("file is not tracked: " + normalized);
        }
        if (of.state != FileState.FINALIZED_REPORT_TEMP) {
            throw new IOException("file is not FINALIZED_REPORT_TEMP: " + of.state);
        }
        // Re-verify identity.
        BasicFileAttributes attrs = readRegularFileAttrs(normalized);
        StrongFileIdentity nowId = StrongFileIdentity.of(normalized, attrs);
        if (!of.finalized.identity().equalsIdentity(nowId)) {
            throw new IOException("IDENTITY_DRIFT_AT_UNSEAL");
        }
        // Re-label as REPORT_UNSEALED usage.
        FinalizedEntry unsealed = new FinalizedEntry(
                of.finalized.canonicalRelativePath(),
                normalized, nowId,
                of.finalized.byteCount(), of.finalized.sha256Hex(),
                FileUsage.REPORT_UNSEALED);
        files.put(normalized, new OwnedFile(of.pending, unsealed, FileState.REPORT_UNSEALED));
    }

    /**
     * Transition an unsealed report to REPORT_SEALED after final scan
     * passes. Once sealed, the report cannot be deleted by the breach
     * handler.
     */
    synchronized void markSealed(Path absolutePath) throws IOException {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        OwnedFile of = files.get(normalized);
        if (of == null) {
            throw new IOException("file is not tracked: " + normalized);
        }
        if (of.state != FileState.REPORT_UNSEALED) {
            throw new IOException("file is not REPORT_UNSEALED: " + of.state);
        }
        BasicFileAttributes attrs = readRegularFileAttrs(normalized);
        StrongFileIdentity nowId = StrongFileIdentity.of(normalized, attrs);
        if (!of.finalized.identity().equalsIdentity(nowId)) {
            throw new IOException("IDENTITY_DRIFT_AT_SEAL");
        }
        FinalizedEntry sealed = new FinalizedEntry(
                of.finalized.canonicalRelativePath(),
                normalized, nowId,
                of.finalized.byteCount(), of.finalized.sha256Hex(),
                FileUsage.REPORT_SEALED);
        files.put(normalized, new OwnedFile(of.pending, sealed, FileState.REPORT_SEALED));
    }

    /**
     * Mark a file as deleted. The entry is retained for re-proof purposes.
     */
    synchronized void markDeleted(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        OwnedFile of = files.get(normalized);
        if (of != null) {
            files.put(normalized, new OwnedFile(of.pending, of.finalized, FileState.ABSENT_PROVED_AFTER_DELETE));
        }
    }

    synchronized boolean isOwned(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        return files.containsKey(normalized);
    }

    synchronized OwnedFile getOwned(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        return files.get(normalized);
    }

    synchronized List<Path> allOwnedPaths() {
        return List.copyOf(files.keySet());
    }

    /**
     * Prove that a file is owned, is a regular file, and its strong
     * identity matches the recorded identity (pending or finalized).
     * Does NOT prove the parent chain 鈥?caller must do that separately.
     */
    synchronized boolean proveOwnershipAndIdentity(Path absolutePath) throws IOException {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        OwnedFile of = files.get(normalized);
        if (of == null) return false;
        if (of.state == FileState.ABSENT_PROVED_AFTER_DELETE) return false;
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(normalized,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return false;
        }
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) return false;
        StrongFileIdentity nowId = StrongFileIdentity.of(normalized, attrs);
        StrongFileIdentity recorded = of.finalized != null ? of.finalized.identity() : of.pending.identity();
        return recorded.equalsIdentity(nowId);
    }

    /**
     * Prove that the parent chain from evidenceRoot to the file's parent
     * has no symlink/junction/reparse/special file at any level. Also
     * re-proves the root itself via the root proof.
     */
    boolean proveParentChain(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        if (!normalized.startsWith(evidenceRoot)) return false;
        // Re-prove root and complete chain.
        if (rootProof == null || !rootProof.reprove()) return false;
        // Walk from evidenceRoot down to the file's parent.
        Path parent = normalized.getParent();
        if (parent == null) return false;
        Path rel = evidenceRoot.relativize(parent);
        Path current = evidenceRoot;
        int nameCount = rel.getNameCount();
        for (int i = 0; i < nameCount; i++) {
            current = current.resolve(rel.getName(i));
            try {
                BasicFileAttributes attrs = Files.readAttributes(current,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) return false;
                if (isReparsePoint(current, attrs)) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Prove that a path is absent via NOFOLLOW readAttributes returning
     * NoSuchFileException. Any other result is not absence.
     */
    static boolean proveAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException e) {
            return true;
        }
    }

    /**
     * Check if the file is deletable on breach. Only PENDING_CREATED,
     * FINALIZED_EVIDENCE, and REPORT_UNSEALED files are deletable.
     * REPORT_SEALED is never deletable.
     */
    synchronized boolean isDeletableOnBreach(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        OwnedFile of = files.get(normalized);
        if (of == null) return false;
        return switch (of.state) {
            case PENDING_CREATED, FINALIZED_EVIDENCE, FINALIZED_REPORT_TEMP, REPORT_UNSEALED -> true;
            case REPORT_SEALED, ABSENT_PROVED_AFTER_DELETE -> false;
        };
    }

    // ------------------------------------------------------------------

    private static BasicFileAttributes readRegularFileAttrs(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException("NOT_REGULAR_FILE");
        }
        if (isReparsePoint(path, attrs)) {
            throw new IOException("REPARSE_POINT_DETECTED");
        }
        return attrs;
    }

    private static boolean isReparsePoint(Path path, BasicFileAttributes attrs) {
        try {
            Object val = Files.getAttribute(path, "dos:reparsePoint",
                    LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(val);
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (IllegalArgumentException e) {
            // Attribute not recognized on this JDK 鈥?fall back to
            // isSymbolicLink() || isOther(). Symlinks and non-directory
            // reparse points are caught. Junctions are caught by the
            // strong identity check.
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException | RuntimeException e) {
            // Fail-closed: cannot read reparse attribute.
            return true;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ------------------------------------------------------------------
    // Reconciliation views used by EvidenceSecretScanner
    // ------------------------------------------------------------------

    /**
     * Whether every registered file stream has been sealed.
     *
     * <p>Treatment of the states is deliberately conservative: a file still in
     * {@link FileState#PENDING_CREATED} (created, never finalized) or
     * {@link FileState#REPORT_UNSEALED} (report stream still open) counts as pending,
     * because the scanner must not reconcile a tree that is still being written.
     *
     * @return true iff no registered file is in a pending or unsealed state
     */
    synchronized boolean hasNoPendingStreams() {
        for (OwnedFile owned : files.values()) {
            if (owned.state == FileState.PENDING_CREATED || owned.state == FileState.REPORT_UNSEALED) {
                return false;
            }
        }
        return true;
    }

    /**
     * The directories this inventory owns: the evidence root plus every ancestor
     * directory of every registered file, up to (but not above) the root.
     *
     * <p>The inventory registers files, not directories, so the directory view is
     * derived from the registered file paths. That is the only directory set the
     * inventory can prove ownership of, and it is what makes the scanner's
     * bidirectional directory reconciliation meaningful: a directory on disk that is
     * not in this set was not created by this run.
     *
     * @return the owned directory paths
     */
    synchronized Set<Path> directoryPathSet() {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(evidenceRoot);
        for (OwnedFile owned : files.values()) {
            Path path = pathOf(owned);
            if (path == null) {
                continue;
            }
            Path parent = path.getParent();
            while (parent != null && parent.startsWith(evidenceRoot) && !parent.equals(evidenceRoot)) {
                directories.add(parent);
                parent = parent.getParent();
            }
        }
        return Set.copyOf(directories);
    }

    /**
     * The files that must exist on disk when the final evidence scan runs: every
     * finalized evidence file plus the sealed report.
     *
     * <p>Files that are still pending, unsealed, or already proved deleted are
     * excluded, so an extra file on disk is reported as unknown rather than silently
     * accepted.
     *
     * @return the expected final file paths
     */
    synchronized Set<Path> finalizedAndReportPathSet() {
        Set<Path> expected = new LinkedHashSet<>();
        for (OwnedFile owned : files.values()) {
            if (owned.state == FileState.FINALIZED_EVIDENCE || owned.state == FileState.REPORT_SEALED) {
                Path path = pathOf(owned);
                if (path != null) {
                    expected.add(path);
                }
            }
        }
        return Set.copyOf(expected);
    }

    private static Path pathOf(OwnedFile owned) {
        if (owned.finalized != null) {
            return owned.finalized.absolutePath();
        }
        if (owned.pending != null) {
            return owned.pending.absolutePath();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    enum FileUsage {
        EVIDENCE,
        REPORT_TEMP,
        REPORT_UNSEALED,
        REPORT_SEALED
    }

    enum FileState {
        PENDING_CREATED,
        FINALIZED_EVIDENCE,
        FINALIZED_REPORT_TEMP,
        REPORT_UNSEALED,
        REPORT_SEALED,
        ABSENT_PROVED_AFTER_DELETE
    }

    record PendingCreation(
            String canonicalRelativePath,
            Path absolutePath,
            StrongFileIdentity identity,
            FileUsage usage
    ) {}

    record FinalizedEntry(
            String canonicalRelativePath,
            Path absolutePath,
            StrongFileIdentity identity,
            long byteCount,
            String sha256Hex,
            FileUsage usage
    ) {}

    static final class OwnedFile {
        final PendingCreation pending;
        final FinalizedEntry finalized;
        final FileState state;

        OwnedFile(PendingCreation pending, FinalizedEntry finalized, FileState state) {
            this.pending = pending;
            this.finalized = finalized;
            this.state = state;
        }
    }
}