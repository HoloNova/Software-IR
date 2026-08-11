package io.kcg.sir.application.conformance;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * One entry in the append-only owned-path inventory.
 *
 * <p>The filesystem identity is captured as a composite of the NOFOLLOW
 * {@code fileKey} (when the platform exposes one) and the
 * {@code lastModifiedTime} as a fallback. On platforms where
 * {@code fileKey()} returns {@code null} (e.g. Windows), the
 * {@code lastModifiedTime} provides the secondary identity signal used
 * for tamper detection during cleanup.
 *
 * @param relativePath     path relative to the owned root, using forward slashes
 * @param isDirectory      whether the entry is a directory
 * @param fileKey          NOFOLLOW filesystem identity key (may be null on Windows)
 * @param lastModifiedTime NOFOLLOW last-modified time (never null)
 * @param byteCount        file size in bytes (0 for directories)
 */
public record OwnedPathEntry(
        String relativePath,
        boolean isDirectory,
        Object fileKey,
        FileTime lastModifiedTime,
        long byteCount
) {
    public OwnedPathEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
    }

    static OwnedPathEntry of(Path root, Path absolute, BasicFileAttributes attrs) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(absolute, "absolute");
        Objects.requireNonNull(attrs, "attrs");
        String rel = root.relativize(absolute).toString().replace('\\', '/');
        return new OwnedPathEntry(
                rel,
                attrs.isDirectory(),
                attrs.fileKey(),
                attrs.lastModifiedTime(),
                attrs.size());
    }

    /**
     * Identity comparison used by the cleanup path. Two entries match
     * when both fileKeys are present and equal, or both are absent and
     * the fallback signals agree. This is the strict fail-closed
     * comparison: a present-vs-absent fileKey mismatch is always a failure.
     *
     * <p>For directories, {@code lastModifiedTime} is not a stable identity
     * signal because it changes when children are added or removed. When
     * {@code fileKey} is absent (e.g. on Windows), directory identity
     * cannot be verified via timestamps alone; the caller must rely on
     * the child-set / emptiness check instead.
     */
    boolean identityMatches(BasicFileAttributes attrs) {
        Objects.requireNonNull(attrs, "attrs");
        Object observedKey = attrs.fileKey();
        if (fileKey != null && observedKey != null) {
            if (isDirectory) {
                // Directories: fileKey is the only stable signal. Don't
                // require lastModifiedTime match (it changes with children).
                return fileKey.equals(observedKey);
            }
            return fileKey.equals(observedKey)
                    && lastModifiedTime.equals(attrs.lastModifiedTime());
        }
        if (fileKey == null && observedKey == null) {
            if (isDirectory) {
                // Windows: directory fileKey is null. lastModifiedTime is
                // not stable for directories. Rely on caller's child-set
                // check for tamper detection.
                return true;
            }
            return lastModifiedTime.equals(attrs.lastModifiedTime())
                    && byteCount == attrs.size();
        }
        // One side has a fileKey, the other doesn't 鈥?fail closed.
        return false;
    }

    /**
     * Compare two fileKeys for equality, treating {@code null} as
     * "unknown" rather than "equal to another null". Two null keys are
     * considered non-equal so that the caller falls back to the
     * composite identity check via {@link #identityMatches}.
     */
    static boolean fileKeysAgree(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}