package io.kcg.sir.application.conformance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only inventory of direct and controlled-child process creations
 * under an owned root. Entries are keyed by relative path and never replaced;
 * identity transitions are recorded as new append-only observations.
 */
public final class OwnedPathInventory {

    private final Map<String, OwnedPathEntry> entries = new LinkedHashMap<>();
    private final List<InventoryTransition> transitions = new ArrayList<>();

    public OwnedPathInventory() {
    }

    /**
     * Record a newly created or observed entry. If an entry with the same
     * relative path already exists, the identity (fileKey) must match;
     * otherwise an {@link IdentityReplacementException} is thrown.
     */
    public synchronized void add(OwnedPathEntry entry) {
        Objects.requireNonNull(entry, "entry");
        OwnedPathEntry existing = entries.get(entry.relativePath());
        if (existing != null) {
            // Use Objects.equals to tolerate null fileKeys on Windows.
            // When both keys are null we cannot detect identity replacement
            // via fileKey alone; the caller's child-set check provides
            // additional tamper detection.
            if (!java.util.Objects.equals(existing.fileKey(), entry.fileKey())) {
                throw new IdentityReplacementException(entry.relativePath(),
                        existing.fileKey(), entry.fileKey());
            }
            // Same identity 鈥?no-op (recheck).
            return;
        }
        entries.put(entry.relativePath(), entry);
        transitions.add(new InventoryTransition("ADD", entry.relativePath(),
                null, entry.fileKey()));
    }

    /**
     * Record that an entry was deleted by the controlled cleanup process.
     */
    public synchronized void markDeleted(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        OwnedPathEntry existing = entries.get(relativePath);
        Object oldKey = existing != null ? existing.fileKey() : null;
        transitions.add(new InventoryTransition("DELETE", relativePath,
                oldKey, null));
        entries.remove(relativePath);
    }

    /**
     * @return an unmodifiable snapshot of current entries.
     */
    public synchronized List<OwnedPathEntry> snapshot() {
        return List.copyOf(entries.values());
    }

    /**
     * @return entries sorted bottom-up (longest relative path first) for
     *         cleanup ordering.
     */
    public synchronized List<OwnedPathEntry> snapshotBottomUp() {
        List<OwnedPathEntry> copy = new ArrayList<>(entries.values());
        copy.sort((a, b) -> {
            // Deeper paths first; within same depth, reverse path order.
            int depthA = countSlashes(a.relativePath());
            int depthB = countSlashes(b.relativePath());
            if (depthA != depthB) {
                return Integer.compare(depthB, depthA);
            }
            return b.relativePath().compareTo(a.relativePath());
        });
        return Collections.unmodifiableList(copy);
    }

    public synchronized boolean contains(String relativePath) {
        return entries.containsKey(relativePath);
    }

    public synchronized OwnedPathEntry get(String relativePath) {
        return entries.get(relativePath);
    }

    public synchronized List<InventoryTransition> transitions() {
        return List.copyOf(transitions);
    }

    private static int countSlashes(String path) {
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    /**
     * Thrown when an observed filesystem identity differs from the recorded
     * identity for the same relative path. This is a fail-closed condition.
     */
    public static final class IdentityReplacementException extends RuntimeException {
        private final String relativePath;
        private final Object expectedKey;
        private final Object actualKey;

        IdentityReplacementException(String relativePath, Object expectedKey, Object actualKey) {
            super("identity replacement at " + relativePath
                    + ": expected " + expectedKey + " but found " + actualKey);
            this.relativePath = relativePath;
            this.expectedKey = expectedKey;
            this.actualKey = actualKey;
        }

        public String relativePath() {
            return relativePath;
        }

        public Object expectedKey() {
            return expectedKey;
        }

        public Object actualKey() {
            return actualKey;
        }
    }

    /**
     * One append-only transition record.
     *
     * @param action       "ADD" or "DELETE"
     * @param relativePath the relative path
     * @param fromKey      previous identity (null for ADD)
     * @param toKey        new identity (null for DELETE)
     */
    public record InventoryTransition(String action, String relativePath,
                                      Object fromKey, Object toKey) {
        public InventoryTransition {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(relativePath, "relativePath");
        }
    }
}