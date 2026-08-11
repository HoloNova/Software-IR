package io.kcg.sir.application.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable creation-time identity of the evidence root directory and its
 * complete parent chain.
 *
 * <p>Per P0-3 design 搂3.1, this records:
 * <ul>
 *   <li>caller parent path;</li>
 *   <li>raw absolute root path;</li>
 *   <li>normalized absolute root path;</li>
 *   <li>the strong identity of every existing directory in the raw and
 *       normalized path chains, from the filesystem root down to the
 *       evidence root;</li>
 *   <li>the root directory's own strong identity;</li>
 *   <li>explicit reparse-point status (must be false on DOS provider).</li>
 * </ul>
 *
 * <p>{@link #reprove()} re-reads every recorded identity and returns
 * {@code false} if any has drifted, been replaced, or become inaccessible.
 * It is called before and after every scanner, deletion, report-write, and
 * report-publish operation.
 */
final class EvidenceRootProof {

    private final Path callerParent;
    private final Path rawRoot;
    private final Path normalizedRoot;
    private final List<ChainEntry> rawChain;
    private final List<ChainEntry> normalizedChain;
    private final StrongFileIdentity rootIdentity;
    private final boolean reparseStatusExplicitlyFalse;

    private EvidenceRootProof(Path callerParent,
                              Path rawRoot,
                              Path normalizedRoot,
                              List<ChainEntry> rawChain,
                              List<ChainEntry> normalizedChain,
                              StrongFileIdentity rootIdentity,
                              boolean reparseStatusExplicitlyFalse) {
        this.callerParent = callerParent;
        this.rawRoot = rawRoot;
        this.normalizedRoot = normalizedRoot;
        this.rawChain = List.copyOf(rawChain);
        this.normalizedChain = List.copyOf(normalizedChain);
        this.rootIdentity = rootIdentity;
        this.reparseStatusExplicitlyFalse = reparseStatusExplicitlyFalse;
    }

    /**
     * Capture the root proof immediately after the evidence root directory
     * has been created via single-directory {@code Files.createDirectory}.
     *
     * @param callerParent the parent passed by the caller
     * @param rawRoot      the raw absolute root path (before normalize)
     * @param normalizedRoot the normalized absolute root path
     * @return the immutable root proof
     * @throws IOException if any directory in the chain cannot be read or
     *                     if strong identity is unavailable
     */
    static EvidenceRootProof capture(Path callerParent,
                                     Path rawRoot,
                                     Path normalizedRoot) throws IOException {
        Objects.requireNonNull(rawRoot, "rawRoot");
        Objects.requireNonNull(normalizedRoot, "normalizedRoot");
        List<ChainEntry> rawChain = captureChain(rawRoot);
        List<ChainEntry> normChain = captureChain(normalizedRoot);

        // Root identity
        BasicFileAttributes rootAttrs = readDirAttrs(normalizedRoot);
        StrongFileIdentity rootId = StrongFileIdentity.of(normalizedRoot, rootAttrs);

        // Reparse status: must be explicitly false on DOS provider.
        boolean reparseFalse = checkReparseFalse(normalizedRoot, rootAttrs);

        return new EvidenceRootProof(
                callerParent, rawRoot, normalizedRoot,
                rawChain, normChain, rootId, reparseFalse);
    }

    /**
     * Re-prove the root and complete chain. Every directory identity must
     * match the creation-time record. Any drift, replacement, symlink,
     * junction, reparse point, I/O error, or SecurityException causes
     * this method to return {@code false}.
     *
     * @return true if the root and chain are unchanged
     */
    boolean reprove() {
        try {
            if (!reproveChain(rawChain)) return false;
            if (!reproveChain(normalizedChain)) return false;
            // Root itself
            BasicFileAttributes rootAttrs;
            try {
                rootAttrs = readDirAttrs(normalizedRoot);
            } catch (IOException e) {
                return false;
            }
            if (rootAttrs.isSymbolicLink() || rootAttrs.isOther()) return false;
            StrongFileIdentity nowId = StrongFileIdentity.of(normalizedRoot, rootAttrs);
            if (!rootIdentity.equalsIdentity(nowId)) return false;
            // Reparse must still be false.
            if (!checkReparseFalse(normalizedRoot, rootAttrs)) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    Path normalizedRoot() {
        return normalizedRoot;
    }

    private static List<ChainEntry> captureChain(Path leaf) throws IOException {
        List<ChainEntry> entries = new ArrayList<>();
        // Walk from filesystem root down to leaf, capturing each directory.
        List<Path> components = new ArrayList<>();
        for (Path p = leaf.toAbsolutePath(); p != null; p = p.getParent()) {
            components.add(p);
        }
        Collections.reverse(components);
        for (Path component : components) {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(component,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                // Non-existent component in the chain 鈥?skip (shouldn't
                // happen for the leaf which was just created, but parents
                // could theoretically not exist if the path is weird).
                continue;
            }
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException(
                        "CHAIN_ELEMENT_NOT_REAL_DIR: " + component);
            }
            StrongFileIdentity id = StrongFileIdentity.of(component, attrs);
            entries.add(new ChainEntry(component, id));
        }
        return entries;
    }

    private boolean reproveChain(List<ChainEntry> chain) {
        for (ChainEntry entry : chain) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(entry.path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) return false;
                if (!attrs.isDirectory()) return false;
                StrongFileIdentity now = StrongFileIdentity.of(entry.path, attrs);
                if (!entry.identity.equalsIdentity(now)) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static BasicFileAttributes readDirAttrs(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException("NOT_REAL_DIR: " + path);
        }
        return attrs;
    }

    /**
     * Check that the path is NOT a reparse point. On DOS provider, the
     * {@code dos:reparsePoint} attribute must be explicitly read and must
     * be {@code false}. On non-DOS provider, this is not applicable and
     * returns {@code true}.
     *
     * <p>Per design 搂3.1: attribute read errors, permission errors, and
     * SecurityException are NOT interpreted as "false" 鈥?they cause this
     * method to return {@code false} (fail-closed).
     *
     * <p>If the {@code dos:reparsePoint} attribute is not recognized
     * (e.g. on JDKs where the DOS view does not expose it), fall back
     * to checking {@code isSymbolicLink() || isOther()} from the
     * already-read attributes. This catches symlinks and non-directory
     * reparse points. Junctions are additionally detected via the
     * strong identity check, which fails if a directory is replaced
     * with a junction to a different target.
     */
    private static boolean checkReparseFalse(Path path, BasicFileAttributes attrs) {
        try {
            Object val = Files.getAttribute(path, "dos:reparsePoint",
                    LinkOption.NOFOLLOW_LINKS);
            return Boolean.FALSE.equals(val);
        } catch (UnsupportedOperationException e) {
            // Non-DOS provider 鈥?reparse check not applicable.
            return true;
        } catch (IllegalArgumentException e) {
            // Attribute not recognized on this JDK 鈥?fall back to
            // isSymbolicLink() || isOther(). This catches symlinks and
            // non-directory reparse points. Junctions (directory reparse
            // points) are caught by the strong identity check.
            return !attrs.isSymbolicLink() && !attrs.isOther();
        } catch (IOException | RuntimeException e) {
            // Any error (including SecurityException, which extends
            // RuntimeException) is fail-closed.
            return false;
        }
    }

    private record ChainEntry(Path path, StrongFileIdentity identity) {}
}