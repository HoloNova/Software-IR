package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Persistent, sanitized evidence directory. Unlike {@link OwnedRunDirectory},
 * the evidence root is intentionally persistent and is not a cleanup target.
 *
 * <p>The run-specific evidence directory is proved absent before creation
 * (using the same NOFOLLOW {@code readAttributes} 鈫?{@code NoSuchFileException}
 * proof) and is never overwritten. Every report/log file uses
 * {@code CREATE_NEW}.
 *
 * <p>Per ADR-017 搂6 the harness forbids {@code Files.exists},
 * {@code Files.notExists}, and {@code createDirectories} as
 * ownership/absence/parent-chain creation evidence. Every existence check
 * uses NOFOLLOW {@code readAttributes}; every parent directory creation
 * uses single-directory {@code createDirectory} after the parent has been
 * proved absent (or re-proved as a real directory).
 */
public final class EvidenceDirectory implements AutoCloseable {

    private final Path root;
    private EvidenceRootProof rootProof;
    private boolean closed;

    private EvidenceDirectory(Path root) {
        this.root = root;
    }

    /**
     * Create a new evidence directory under the given parent.
     *
     * @param parent    the caller-supplied parent (must be a real directory)
     * @param childName the run-specific evidence directory name
     * @return the evidence directory
     * @throws IOException if the directory cannot be created or already exists
     */
    public static EvidenceDirectory create(Path parent, String childName) throws IOException {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(childName, "childName");
        if (childName.isBlank()) {
            throw new IllegalArgumentException("childName must not be blank");
        }
        OwnedRunDirectory.validateParentChain(parent);
        Path root = parent.resolve(childName).toAbsolutePath().normalize();
        // Prove absence: only NoSuchFileException from NOFOLLOW readAttributes
        // proves the path does not exist. Any other I/O exception, symlink,
        // or existing object is a conflict.
        proveAbsent(root);
        // Single-directory create 鈥?never createDirectories.
        Files.createDirectory(root);
        // Verify the created root is a real directory (no symlink swap).
        BasicFileAttributes attrs = Files.readAttributes(root,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) {
            throw new IOException("created evidence root is not a real directory: " + root);
        }
        return new EvidenceDirectory(root);
    }

    public Path root() {
        return root;
    }

    /**
     * Attach the creation-time chain proof so {@link #reproveRoot()} can verify the
     * whole parent chain, not just the root's own attributes.
     *
     * <p>Called once by the orchestration immediately after creation. When no proof is
     * attached, {@link #reproveRoot()} falls back to reading the root's attributes; that
     * fallback cannot detect a swapped ancestor, which is why the run always attaches the
     * proof.
     *
     * @param rootProof the immutable creation-time proof
     */
    void attachRootProof(EvidenceRootProof rootProof) {
        this.rootProof = Objects.requireNonNull(rootProof, "rootProof");
    }

    /**
     * Re-prove that the evidence root is still the directory this run created.
     *
     * <p>The evidence scanner calls this immediately before and after its traversal. A
     * root (or ancestor) that has been swapped for a link between those two points would
     * mean the scan inspected a different tree than the one this run created, so the proof
     * is re-run rather than trusted from creation time.
     *
     * @return true iff the root and its chain are unchanged
     */
    public boolean reproveRoot() {
        if (rootProof != null) {
            return rootProof.reprove();
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(root,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isDirectory() && !attrs.isSymbolicLink();
        } catch (IOException e) {
            // A root that cannot be read is not a proven root.
            return false;
        }
    }

    /**
     * Resolve a relative path under the evidence root, creating parent
     * directories one at a time using single-directory creates. Each
     * parent is first proved absent (NOFOLLOW readAttributes returning
     * NoSuchFileException) or re-proved as a real directory before use.
     *
     * <p>The final path component is NOT created by this method 鈥?the
     * caller is responsible for creating it as a file (e.g. via
     * {@code Files.write(..., CREATE_NEW)}) or as a directory (via
     * {@code Files.createDirectory}). This avoids the ambiguity where
     * {@code resolve("a/b/c")} could be intended as a directory tree or
     * as a file path under {@code a/b/}.
     *
     * <p>{@code Files.exists}, {@code Files.notExists}, and
     * {@code createDirectories} are forbidden as evidence here.
     */
    public Path resolve(String relativePath) throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "path escapes evidence root: " + relativePath);
        }
        // Walk each ancestor from root downward (excluding the final
        // component), creating single directories only when proven absent.
        Path rel = root.relativize(resolved);
        int nameCount = rel.getNameCount();
        Path current = root;
        // Only iterate over ancestors of the final component 鈥?the final
        // component itself is left for the caller to create.
        for (int i = 0; i < nameCount - 1; i++) {
            current = current.resolve(rel.getName(i));
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(current,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                // Proven absent 鈥?single-directory create.
                Files.createDirectory(current);
                continue;
            }
            // Existing 鈥?must be a real directory, no symlink.
            if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                throw new IOException("evidence path element is not a real directory: " + current);
            }
        }
        return resolved;
    }

    /**
     * Resolve a relative directory path under the evidence root, creating
     * every component (including the final one) as a single directory
     * after NOFOLLOW absence proof. Use this when the caller intends the
     * final component to be a directory rather than a file.
     */
    public Path resolveDirectory(String relativePath) throws IOException {
        Path resolved = resolve(relativePath);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(resolved,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            // Proven absent 鈥?single-directory create.
            Files.createDirectory(resolved);
            return resolved;
        }
        // Existing 鈥?must be a real directory, no symlink.
        if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
            throw new IOException("evidence path is not a real directory: " + resolved);
        }
        return resolved;
    }

    /**
     * Prove that the given path is absent. Only a
     * {@code NoSuchFileException} from NOFOLLOW {@code readAttributes} proves
     * absence. Any other I/O exception, symlink, or existing object is a
     * conflict.
     */
    static void proveAbsent(Path path) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new FileAlreadyExistsException(
                    "path already exists: " + path + " (type=" + typeOf(attrs) + ")");
        } catch (NoSuchFileException e) {
            // This is the only acceptable proof of absence.
        }
    }

    private static String typeOf(BasicFileAttributes attrs) {
        if (attrs.isDirectory()) {
            return "directory";
        }
        if (attrs.isRegularFile()) {
            return "regular file";
        }
        if (attrs.isSymbolicLink()) {
            return "symlink";
        }
        return "special";
    }

    @Override
    public void close() {
        // Evidence directory is persistent 鈥?no cleanup.
        closed = true;
    }
}