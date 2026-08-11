package io.kcg.cli.mvp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evidence-root ownership and final-scan controller for the M4 acceptance IT
 * (ADR-019 搂14, P0-3). Implements the strict test-only equivalent of the
 * Stage E evidence ownership semantics:
 *
 * <ul>
 *   <li>raw/normalized path-chain proof: every component of the absolute
 *       normalized root is re-read with {@code NOFOLLOW_LINKS} and must be a
 *       real directory (no symlink/junction/reparse anywhere in the chain);</li>
 *   <li>append-only ownership inventory: the writer registers each file it
 *       intends to create (PENDING) and records identity/type/size/hash
 *       after writing;</li>
 *   <li>final scan: re-proves the chain before AND after the tree walk,
 *       walks the complete evidence tree with {@code NOFOLLOW_LINKS},
 *       detects unknown/missing/PENDING/symlink/special/identity/size/hash
 *       drift, and scans every file's bytes for credentials;</li>
 *   <li>a credential breach deletes ONLY inventory-proven owned dirty files
 *       and fails; no sensitive report is published.</li>
 * </ul>
 */
final class MvpEvidenceRoot {

    /** One owned file: PENDING until written, then identity/size/hash recorded. */
    static final class Owned {
        final String relativePath;
        boolean pending = true;
        String identity = "<none>";
        long byteCount = -1;
        String sha256Hex = null;

        Owned(String relativePath) {
            this.relativePath = relativePath;
        }
    }

    private final Path root;
    private final Map<String, Owned> owned = new TreeMap<>();

    private MvpEvidenceRoot(Path root) {
        this.root = root;
    }

    /** Prove the raw/normalized chain, then create the (absent) leaf directory. */
    static MvpEvidenceRoot create(Path root) throws IOException {
        Path abs = root.toAbsolutePath().normalize();
        proveChain(abs, true);
        Files.createDirectory(abs);
        proveChain(abs, false);
        return new MvpEvidenceRoot(abs);
    }

    /**
     * NOFOLLOW chain proof: every component from the drive root down to the
     * leaf must be a real directory (no symlink/junction/reparse point) and
     * the leaf must be absent (when {@code leafAbsent}) or a directory.
     */
    static void proveChain(Path abs, boolean leafAbsent) throws IOException {
        Path prefix = abs.getRoot();
        if (prefix == null) {
            throw new AssertionError("evidence root has no root component: " + abs);
        }
        checkComponent(prefix, false);
        for (Path part : abs) {
            prefix = prefix.resolve(part);
            boolean leaf = prefix.equals(abs);
            if (leaf && leafAbsent) {
                try {
                    Files.readAttributes(prefix, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    throw new AssertionError("evidence path must not pre-exist: " + prefix);
                } catch (NoSuchFileException expected) {
                    return;
                }
            }
            checkComponent(prefix, leaf && !leafAbsent);
        }
    }

    private static void checkComponent(Path component, boolean mustBeDirectory) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw new AssertionError("evidence chain component missing: " + component);
        }
        if (attrs.isSymbolicLink() || isReparsePoint(component)) {
            throw new AssertionError("evidence chain contains a symlink/reparse point: " + component);
        }
        if (mustBeDirectory && !attrs.isDirectory()) {
            throw new AssertionError("evidence chain component is not a directory: " + component);
        }
        if (!mustBeDirectory && attrs.isRegularFile()) {
            throw new AssertionError("evidence chain component is a regular file: " + component);
        }
    }

    Path root() {
        return root;
    }

    /** Register an intended owned file (append-only inventory; PENDING). */
    void register(String fileName) {
        if (owned.containsKey(fileName)) {
            throw new AssertionError("duplicate owned file registration: " + fileName);
        }
        owned.put(fileName, new Owned(fileName));
    }

    /** Write an evidence file and record its identity/type/size/hash. */
    void write(MvpEvidence evidence, String fileName) throws IOException {
        Owned o = owned.get(fileName);
        if (o == null) {
            throw new AssertionError("writing unregistered evidence file: " + fileName);
        }
        if (!o.pending) {
            throw new AssertionError("duplicate evidence write: " + fileName);
        }
        byte[] bytes = (evidence.serialize() + "\n").getBytes(StandardCharsets.UTF_8);
        Path target = root.resolve(fileName);
        // [RQ-08 RECOVERY NOTE] M4 hardening: never clobber an existing target.
        // The surviving test evidenceWriteNeverOverwritesAnExistingTarget requires
        // write() to reject a pre-existing file (external pre-write scenario).
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to overwrite existing evidence file: " + fileName);
        }
        Files.write(target, bytes);
        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) {
            throw new AssertionError("evidence file is not a regular file: " + fileName);
        }
        o.pending = false;
        o.identity = attrs.fileKey() == null ? "<none>" : String.valueOf(attrs.fileKey());
        o.byteCount = attrs.size();
        o.sha256Hex = MvpSupport.sha256Hex(bytes);
    }

    /**
     * Final scan: re-prove the chain, walk the complete evidence tree with
     * NOFOLLOW, verify the tree exactly matches the inventory (no unknown,
     * missing, PENDING, symlink, special, identity, size, or hash drift),
     * scan every file's bytes for credentials, and re-prove the chain again.
     * On a credential breach, ONLY inventory-proven owned dirty files are
     * deleted before the failure is reported.
     *
     * @return list of problems (empty when the root is clean)
     */
    List<String> finalScan(String... secrets) throws IOException {
        List<String> problems = new ArrayList<>();
        proveChain(root, false);
        Map<String, Path> found = new TreeMap<>();
        List<String> dirs = new ArrayList<>();
        walkTree(root, root, found, dirs, problems);
        problems.addAll(checkDirs(dirs, owned.keySet()));

        for (Owned o : owned.values()) {
            if (o.pending) {
                problems.add("PENDING:" + o.relativePath);
            }
            Path p = found.get(o.relativePath);
            if (p == null) {
                problems.add("MISSING:" + o.relativePath);
                continue;
            }
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            String identity = attrs.fileKey() == null ? "<none>" : String.valueOf(attrs.fileKey());
            if (!o.identity.equals(identity)) {
                problems.add("IDENTITY:" + o.relativePath);
            }
            if (attrs.size() != o.byteCount) {
                problems.add("SIZE:" + o.relativePath);
            }
            if (!MvpSupport.sha256Hex(Files.readAllBytes(p)).equals(o.sha256Hex)) {
                problems.add("HASH:" + o.relativePath);
            }
            List<String> hits = MvpEvidence.scanBytes(Files.readAllBytes(p), secrets);
            if (!hits.isEmpty()) {
                problems.add("SECRET:" + o.relativePath + ":" + hits);
            }
        }
        for (String rel : found.keySet()) {
            if (!owned.containsKey(rel)) {
                // [RQ-08 RECOVERY NOTE] M4 hardening: unknown files must be scanned for
                // secrets BEFORE the unknown-file reconciliation failure is recorded
                // (test unknownFileContainingSecretIsReportedAndSurvivesOwnedOnlyBreachCleanup).
                List<String> hits = MvpEvidence.scanBytes(Files.readAllBytes(found.get(rel)), secrets);
                if (!hits.isEmpty()) {
                    problems.add("SECRET:" + rel + ":" + hits);
                }
                problems.add("UNKNOWN:" + rel);
            }
        }
        proveChain(root, false);

        // Credential breach: delete ONLY inventory-proven owned dirty files.
        if (hasSecretProblems(problems)) {
            for (Owned o : owned.values()) {
                Path p = root.resolve(o.relativePath);
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; the failure is already recorded
                }
            }
        }
        return problems;
    }

    private static boolean hasSecretProblems(List<String> problems) {
        for (String p : problems) {
            if (p.startsWith("SECRET:")) {
                return true;
            }
        }
        return false;
    }

    private static void walkTree(Path base, Path dir, Map<String, Path> found,
                                 List<String> dirs, List<String> problems) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                BasicFileAttributes attrs = Files.readAttributes(
                        child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String rel = base.relativize(child.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                if (attrs.isDirectory()) {
                    if (attrs.isSymbolicLink() || isReparsePoint(child)) {
                        problems.add("SYMLINK:" + rel);
                    }
                    dirs.add(rel);
                    walkTree(base, child, found, dirs, problems);
                } else if (attrs.isRegularFile()) {
                    found.put(rel, child);
                } else if (attrs.isSymbolicLink() || isReparsePoint(child)) {
                    problems.add("SYMLINK:" + rel);
                } else {
                    problems.add("SPECIAL:" + rel);
                }
            }
        }
    }

    /** Every directory must be the root or an implied parent of an owned file. */
    private static List<String> checkDirs(List<String> dirs, java.util.Set<String> ownedFiles) {
        List<String> problems = new ArrayList<>();
        for (String dir : dirs) {
            if (dir.isEmpty()) {
                continue; // the root itself
            }
            boolean implied = false;
            for (String owned : ownedFiles) {
                if (owned.startsWith(dir + "/")) {
                    implied = true;
                    break;
                }
            }
            if (!implied) {
                problems.add("UNKNOWN_DIR:" + dir);
            }
        }
        return problems;
    }

    private static boolean isReparsePoint(Path p) {
        try {
            Object v = Files.getAttribute(p, "dos:isReparsePoint", LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whole-tree verification of a caller-owned invocation root against an
     * explicit owned-file list: chain proof, NOFOLLOW walk, exact file-set
     * match, per-file byte scan, and a second chain proof.
     */
    static List<String> verifyWhole(Path root, List<String> ownedRelativePaths,
                                    String... secrets) throws IOException {
        Path abs = root.toAbsolutePath().normalize();
        List<String> problems = new ArrayList<>();
        proveChain(abs, false);
        Map<String, Path> found = new TreeMap<>();
        List<String> dirs = new ArrayList<>();
        walkTree(abs, abs, found, dirs, problems);
        problems.addAll(checkDirs(dirs, new java.util.HashSet<>(ownedRelativePaths)));
        for (String rel : ownedRelativePaths) {
            if (!found.containsKey(rel)) {
                problems.add("MISSING:" + rel);
            }
        }
        for (Map.Entry<String, Path> e : found.entrySet()) {
            if (!ownedRelativePaths.contains(e.getKey())) {
                problems.add("UNKNOWN:" + e.getKey());
            } else {
                List<String> hits = MvpEvidence.scanBytes(Files.readAllBytes(e.getValue()), secrets);
                if (!hits.isEmpty()) {
                    problems.add("SECRET:" + e.getKey() + ":" + hits);
                }
            }
        }
        proveChain(abs, false);
        return problems;
    }
}
