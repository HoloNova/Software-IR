package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Final secret scanner that scans every evidence file before report
 * publication. If any registered secret representation is found, or if the
 * scanner cannot be initialized, the run must stop with
 * {@code FAILED(HARNESS_CREDENTIAL_BOUNDARY)}.
 *
 * <p>Only sanitized evidence is retained. Unredacted temporary material is
 * not published or retained.
 *
 * <p>P0-3: The scanner consumes the same canonical {@link SecretCatalog}
 * as the {@link StreamingSecretRedactor}, ensuring that both components
 * share an identical secret representation. Byte-level scanning is performed
 * in addition to pattern-based string scanning so that raw, percent-encoded,
 * user-info, and config-key forms are all detected.
 */
public final class EvidenceSecretScanner {

    private final List<Pattern> secretPatterns;
    private final List<byte[]> secretBytes;

    private EvidenceSecretScanner(List<Pattern> secretPatterns, List<byte[]> secretBytes) {
        this.secretPatterns = secretPatterns;
        this.secretBytes = secretBytes;
    }

    /**
     * Initialize the scanner with exact secret values. Returns null (failure)
     * if the list is empty or any secret is null or empty.
     */
    public static EvidenceSecretScanner initialize(List<String> secrets) {
        Objects.requireNonNull(secrets, "secrets");
        if (secrets.isEmpty()) {
            return null;
        }
        List<Pattern> patterns = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        for (String s : secrets) {
            if (s == null || s.isEmpty()) {
                return null;
            }
            patterns.add(Pattern.compile(Pattern.quote(s)));
            bytes.add(s.getBytes(StandardCharsets.UTF_8));
        }
        return new EvidenceSecretScanner(List.copyOf(patterns), List.copyOf(bytes));
    }

    /**
     * P0-3: Initialize the scanner from a shared {@link SecretCatalog}.
     */
    public static EvidenceSecretScanner from(SecretCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return new EvidenceSecretScanner(
                catalog.allSecretPatterns(),
                catalog.allSecretBytes());
    }

    /**
     * Scan a single file for any registered secret representation.
     */
    public boolean scanFile(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        byte[] bytes = Files.readAllBytes(file);
        for (byte[] secret : secretBytes) {
            if (containsBytes(bytes, secret)) {
                return false;
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (Pattern p : secretPatterns) {
            if (p.matcher(text).find()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // P0-3 搂3.3: Full tree reconciliation scan
    // ------------------------------------------------------------------

    /**
     * Stable, path-free result of a reconciliation scan. Only exposes
     * a failure key; never paths, secrets, or file content.
     */
    public sealed interface ScanResult {
        record Clean() implements ScanResult {}
        record Dirty(String failureKey) implements ScanResult {}
        static ScanResult clean() { return new Clean(); }
        static ScanResult dirty(String key) { return new Dirty(key); }
    }

    /**
     * Full reconciliation scan combining secret-content scanning,
     * inventory tree-shape verification, and root/chain proof.
     *
     * <p>Per P0-3 搂3.3:
     * <ul>
     *   <li>reprove root and chain BEFORE traversal</li>
     *   <li>collect ALL disk objects (directories + files) via NOFOLLOW</li>
     *   <li>verify every disk entry is a real directory or regular file</li>
     *   <li>scan every regular file for secret content</li>
     *   <li>reconcile disk object set against the inventory</li>
     *   <li>reprove root and chain AFTER traversal</li>
     * </ul>
     *
     * <p>PENDING_CREATED files, unknown files, missing inventory entries,
     * symlinks, junctions, reparse points, special files, identity drift,
     * and I/O errors all produce {@link ScanResult.Dirty} with a stable key.
     */
    public ScanResult scanAndReconcile(
            EvidenceDirectory evidenceDirectory,
            EvidenceOwnershipInventory inventory) {
        Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
        Objects.requireNonNull(inventory, "inventory");
        Path root = evidenceDirectory.root();

        // 0. Hard gate: reject any PENDING_CREATED stream BEFORE scan.
        if (!inventory.hasNoPendingStreams()) {
            return ScanResult.dirty("PENDING_STREAMS_AT_SCAN_GATE");
        }

        // 1. Reproof root BEFORE traversal.
        if (!evidenceDirectory.reproveRoot()) {
            return ScanResult.dirty("ROOT_PROOF_FAILED_BEFORE_SCAN");
        }

        // 2. Collect all disk objects (directories + files).
        Set<Path> diskDirs;
        Set<Path> diskFiles;
        try {
            DiskTree tree = collectDiskTree(root);
            diskDirs = tree.directories;
            diskFiles = tree.files;
        } catch (IOException e) {
            return ScanResult.dirty("SCAN_IO_ERROR");
        }

        // 3. Scan every regular file for secret content.
        for (Path f : diskFiles) {
            try {
                if (!scanFile(f)) {
                    return ScanResult.dirty("SECRET_FOUND_IN_EVIDENCE");
                }
            } catch (IOException e) {
                return ScanResult.dirty("SCAN_FILE_READ_ERROR");
            }
        }

        // 4. Reconcile directories: bidirectional exact match.
        Set<Path> registeredDirs = inventory.directoryPathSet();
        for (Path rd : registeredDirs) {
            if (!diskDirs.contains(rd)) {
                return ScanResult.dirty("MISSING_EVIDENCE_DIRECTORY");
            }
        }
        for (Path dd : diskDirs) {
            if (!registeredDirs.contains(dd)) {
                return ScanResult.dirty("UNREGISTERED_DIRECTORY_IN_EVIDENCE_TREE");
            }
        }

        // 5. Reconcile files: disk files must exactly equal finalized set.
        Set<Path> expectedFiles = inventory.finalizedAndReportPathSet();
        if (!diskFiles.equals(expectedFiles)) {
            Set<Path> unknown = new LinkedHashSet<>(diskFiles);
            unknown.removeAll(expectedFiles);
            Set<Path> missing = new LinkedHashSet<>(expectedFiles);
            missing.removeAll(diskFiles);
            if (!unknown.isEmpty()) {
                return ScanResult.dirty("UNKNOWN_FILE_IN_EVIDENCE_TREE");
            }
            if (!missing.isEmpty()) {
                return ScanResult.dirty("MISSING_EVIDENCE_FILE");
            }
            return ScanResult.dirty("EVIDENCE_TREE_MISMATCH");
        }

        // 6. Reproof root AFTER traversal.
        if (!evidenceDirectory.reproveRoot()) {
            return ScanResult.dirty("ROOT_PROOF_FAILED_AFTER_SCAN");
        }

        return ScanResult.clean();
    }

    // ------------------------------------------------------------------
    // Legacy methods retained for tests that still use Path-based API
    // ------------------------------------------------------------------

    public boolean scanTree(Path evidenceRoot) throws IOException {
        Objects.requireNonNull(evidenceRoot, "evidenceRoot");
        List<Path> files = collectRegularFiles(evidenceRoot);
        for (Path f : files) {
            if (!scanFile(f)) {
                return false;
            }
        }
        return true;
    }

    public List<Path> findDirtyFiles(Path evidenceRoot) throws IOException {
        Objects.requireNonNull(evidenceRoot, "evidenceRoot");
        List<Path> dirty = new ArrayList<>();
        List<Path> files = collectRegularFiles(evidenceRoot);
        for (Path f : files) {
            if (!scanFile(f)) {
                dirty.add(f);
            }
        }
        return dirty;
    }

    // ------------------------------------------------------------------
    // Internal: NOFOLLOW tree collection
    // ------------------------------------------------------------------

    private DiskTree collectDiskTree(Path root) throws IOException {
        Set<Path> dirs = new LinkedHashSet<>();
        Set<Path> files = new LinkedHashSet<>();
        collectDiskTreeRecursive(root, dirs, files);
        return new DiskTree(dirs, files);
    }

    private void collectDiskTreeRecursive(Path dir, Set<Path> dirs, Set<Path> files)
            throws IOException {
        dirs.add(dir);
        DirectoryStream<Path> entries;
        try {
            entries = Files.newDirectoryStream(dir);
        } catch (IOException e) {
            throw new IOException("SCAN_IO_ERROR", e);
        }
        try (entries) {
            for (Path entry : entries) {
                // [RQ-05 RECOVERY NOTE] loop body lost to historical output
                // truncation (250-line cap). Recovery evidence: session block
                // line 9370 output ends here; no other block contains the rest.
                break;
            }
        }
    }

    // [RQ-05 RECOVERY NOTE] File truncated at 250 lines in historical output; remainder lost.
    // Class closed for compilation; missing methods (scan entrypoints etc.) will surface
    // as compile errors at call sites.
}
