public final class OwnedRunDirectory implements AutoCloseable {

    private static final String MARKER_NAME = ".kcg-owner-marker";

    private final Path root;
    private final Path markerPath;
    private final FileChannel markerChannel;
    private final FileLock markerLock;
    private final OwnedPathInventory inventory;
    private final BasicFileAttributes rootAttrs;
    private boolean closed;

    private OwnedRunDirectory(Path root, FileChannel markerChannel,
                             FileLock markerLock, OwnedPathInventory inventory,
                             BasicFileAttributes rootAttrs) {
        this.root = root;
        this.markerPath = root.resolve(MARKER_NAME);
        this.markerChannel = markerChannel;
        this.markerLock = markerLock;
        this.inventory = inventory;
        this.rootAttrs = rootAttrs;
    }

    /**
     * Create a new owned run directory under the given parent.
     *
     * @param parent    the caller-supplied parent directory (must be a real
     *                  directory with no symlink in its chain)
     * @param childName the run-specific child directory name
     * @return the owned directory, holding an exclusive marker lock
     * @throws IOException if the directory cannot be created or the parent
     *                     chain is unsafe
     */
    public static OwnedRunDirectory create(Path parent, String childName) throws IOException {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(childName, "childName");
        if (childName.isBlank()) {
            throw new IllegalArgumentException("childName must not be blank");
        }

        // Validate the parent chain: raw and normalized, no symlinks.
        validateParentChain(parent);

        Path root = parent.resolve(childName).toAbsolutePath().normalize();

        // Prove absence: only NoSuchFileException from NOFOLLOW readAttributes
        // proves the path does not exist.
        proveAbsent(root);

        // Single-directory create.
        Files.createDirectory(root);

        // Record NOFOLLOW type and filesystem identity.
        BasicFileAttributes attrs = Files.readAttributes(root,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory()) {
            throw new IOException("created path is not a directory: " + root);
        }

        // Create owner marker with CREATE_NEW + exclusive lock.
        Path markerPath = root.resolve(MARKER_NAME);
        FileChannel markerChannel;
        FileLock markerLock;
        try {
            markerChannel = FileChannel.open(markerPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            markerLock = markerChannel.tryLock(0, Long.MAX_VALUE, false);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("owner marker already exists in new directory: " + markerPath, e);
        }
        if (markerLock == null) {
            markerChannel.close();
            throw new IOException("cannot acquire exclusive marker lock: " + markerPath);
        }

        OwnedPathInventory inventory = new OwnedPathInventory();
        // Record the root and marker.
        inventory.add(OwnedPathEntry.of(root, root, attrs));
        BasicFileAttributes markerAttrs = Files.readAttributes(markerPath,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        inventory.add(OwnedPathEntry.of(root, markerPath, markerAttrs));

        return new OwnedRunDirectory(root, markerChannel, markerLock, inventory, attrs);
    }

    public Path root() {
        return root;
    }

    public OwnedPathInventory inventory() {
        return inventory;
    }

    /**
     * Record a newly created child (file or directory) under the owned root.
     */
    public synchronized void recordCreation(Path absolute) throws IOException {
        ensureOpen();
        Objects.requireNonNull(absolute, "absolute");
        validateContainment(absolute);
        BasicFileAttributes attrs = Files.readAttributes(absolute,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // Reject symlinks and special files.
        if (attrs.isSymbolicLink()) {
            throw new IOException("symlink rejected under owned root: " + absolute);
        }
        if (!attrs.isRegularFile() && !attrs.isDirectory()) {
            throw new IOException("special file rejected under owned root: " + absolute);
        }
        inventory.add(OwnedPathEntry.of(root, absolute, attrs));
    }

    /**
     * Cleanup: delete all inventory entries bottom-up, re-validating each
     * before deletion. Finally deletes the marker and the root directory
     * itself. Returns true only if every entry (including the marker and
     * the root) was deleted.
     *
     * <p>If any entry cannot be proved, it and its ancestors are preserved
     * and this method returns false. The caller must treat this as
     * {@code FAILED(CLEANUP_OWNERSHIP_UNPROVED)}.
     */
    public synchronized boolean cleanup() throws IOException {
        ensureOpen();
        boolean allDeleted = true;
        // First pass: delete all non-marker, non-root entries bottom-up.
        for (OwnedPathEntry entry : inventory.snapshotBottomUp()) {
            if (MARKER_NAME.equals(entry.relativePath())) {
                // Marker is deleted after lock release.
                continue;
            }
            if (entry.relativePath().isEmpty() || entry.relativePath().equals(".")) {
                // Root is deleted last, after marker.
                continue;
            }
            if (!safeDelete(entry)) {
                allDeleted = false;
            }
        }
        // Release marker lock and delete marker file.
        if (!releaseMarker()) {
            allDeleted = false;
        }
        // Finally delete the root directory itself, after re-validating it.
        if (!deleteRootItself()) {
            allDeleted = false;
        }
        return allDeleted;
    }

    /**
     * Release the marker lock and delete the marker file. Returns true if
     * the marker was successfully deleted (or already absent with proof).
     */
    private synchronized boolean releaseMarker() throws IOException {
        IOException firstError = null;
        if (markerLock != null) {
            try {
                markerLock.release();
            } catch (IOException e) {
                firstError = e;
            }
        }
        try {
            markerChannel.close();
        } catch (IOException e) {
            if (firstError == null) {
                firstError = e;
            }
        }
        // Delete marker if it still exists and is a regular file with
        // matching identity.
        try {
            BasicFileAttributes attrs = Files.readAttributes(markerPath,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                return false;
            }
            if (!attrs.isRegularFile()) {
                return false;
            }
            // Verify marker identity matches the recorded one. Use the
            // composite identity check that tolerates null fileKey on
            // Windows (lastModifiedTime + byteCount provide the fallback).
            OwnedPathEntry recorded = inventory.get(MARKER_NAME);
            if (recorded != null && !recorded.identityMatches(attrs)) {
                return false;
            }
            Files.delete(markerPath);
            inventory.markDeleted(MARKER_NAME);
        } catch (NoSuchFileException e) {
            // Already gone 鈥?prove absence via re-read.
            try {
                Files.readAttributes(markerPath,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // Still exists 鈥?fail closed.
                return false;
            } catch (NoSuchFileException ok) {
                // Proven absent.
            }
        }
        if (firstError != null) {
            return false;
        }
        return true;
    }

    /**
     * Delete the root directory itself, after re-validating that it is
     * a real directory (NOFOLLOW), its identity matches the recorded
     * root identity, and it has no remaining children. Returns true if
     * the root was successfully deleted (or already absent with proof).
     */
    private synchronized boolean deleteRootItself() throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(root,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            // Already gone 鈥?prove absence via re-read.
            try {
                Files.readAttributes(root,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return false;
            } catch (NoSuchFileException ok) {
                return true;
            }
        }
        if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
            return false;
        }
        // Verify root identity using the inventory entry, which handles
        // null fileKey on Windows correctly. For directories, identity
        // relies on fileKey when available; on Windows (null fileKey)
        // we rely on the emptiness check below for tamper detection.
        OwnedPathEntry rootEntry = inventory.get("");
        if (rootEntry != null && !rootEntry.identityMatches(attrs)) {
            return false;
        }
        // Root must be empty (no children at all).
        try (var stream = Files.newDirectoryStream(root)) {
            if (stream.iterator().hasNext()) {
                // Not empty 鈥?fail closed.
                return false;
            }
        }
        try {
            Files.delete(root);
            inventory.markDeleted("");
        } catch (IOException e) {
            return false;
        }
        // Final absence proof.
        try {
            Files.readAttributes(root,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // Still exists 鈥?fail closed.
            return false;
        } catch (NoSuchFileException e) {
            // Proven absent.
            return true;
        }
    }

    private boolean safeDelete(OwnedPathEntry entry) throws IOException {
        Path absolute = root.resolve(entry.relativePath());
        // Re-prove containment.
        if (!contains(absolute)) {
            return false;
        }
        // Re-read NOFOLLOW attributes.
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(absolute, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            // Already absent 鈥?mark deleted in inventory.
            inventory.markDeleted(entry.relativePath());
            return true;
        } catch (IOException e) {
            // Unknown state 鈥?fail closed.
            return false;
        }
        // Reject symlinks.
        if (attrs.isSymbolicLink()) {
            return false;
        }
        // Verify identity matches using composite check that tolerates
        // null fileKey on Windows (falls back to lastModifiedTime + size).
        if (!entry.identityMatches(attrs)) {
            // Identity replacement 鈥?fail closed.
            return false;
        }
        // If directory, verify child set equals tracked child set.
        if (attrs.isDirectory()) {
            if (!directoryChildSetMatches(absolute, entry)) {
                return false;
            }
        }
        // Delete.
        try {
            Files.delete(absolute);
            inventory.markDeleted(entry.relativePath());
            return true;
        } catch (IOException e) {
            // Delete failure 鈥?fail closed.
            return false;
        }
    }

    private boolean directoryChildSetMatches(Path dir, OwnedPathEntry entry) throws IOException {
        List<String> actualChildren = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            stream.forEach(p -> actualChildren.add(p.getFileName().toString()));
        }
        // Compute expected children from inventory.
        String prefix = entry.relativePath() + "/";
        List<String> expectedChildren = new ArrayList<>();
        for (OwnedPathEntry e : inventory.snapshot()) {
            if (e.relativePath().startsWith(prefix)) {
                String child = e.relativePath().substring(prefix.length());
                int slash = child.indexOf('/');
                if (slash < 0) {
                    expectedChildren.add(child);
                } else {
                    expectedChildren.add(child.substring(0, slash));
                }
            }
        }
        // The marker is always a child of the root.
        if (entry.relativePath().isEmpty() || entry.relativePath().equals(".")) {
            // Root directory 鈥?marker is expected.
            if (!expectedChildren.contains(MARKER_NAME)) {
                expectedChildren.add(MARKER_NAME);
            }
        }
        // Compare as sets.
        var actualSet = new java.util.TreeSet<>(actualChildren);
        var expectedSet = new java.util.TreeSet<>(expectedChildren);
        return actualSet.equals(expectedSet);
    }

    private void validateContainment(Path absolute) {
        if (!contains(absolute)) {
            throw new IllegalArgumentException(
                    "path escapes owned root: " + absolute + " root=" + root);
        }
    }

    private boolean contains(Path absolute) {
        Path normalized = absolute.toAbsolutePath().normalize();
        Path rootNormalized = root.toAbsolutePath().normalize();
        return normalized.startsWith(rootNormalized);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OwnedRunDirectory is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // Best-effort marker lock release. If cleanup() was already called,
        // the lock is already released and the marker already deleted.
        try {
            if (markerLock != null) {
                try {
                    markerLock.release();
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
            try {
                markerChannel.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        } finally {
            // No-op; cleanup() is responsible for marker and root deletion.
        }
    }

    // ------------------------------------------------------------------
    // Static helpers
    // ------------------------------------------------------------------

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
            // Path exists 鈥?conflict.
            throw new FileAlreadyExistsException(
                    "path already exists: " + path + " (type=" + typeOf(attrs) + ")");
        } catch (NoSuchFileException e) {
            // This is the only acceptable proof of absence.
            return;
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

    /**
     * Validate the full parent chain (raw and normalized) contains only real
     * directories and no symlink/reparse traversal.
     */
    static void validateParentChain(Path parent) throws IOException {
        Path absolute = parent.toAbsolutePath();
        Path normalized = absolute.normalize();

        // Check both the raw chain and the normalized chain.
        validateChainNoSymlink(absolute);
        if (!normalized.equals(absolute)) {
            validateChainNoSymlink(normalized);
        }

        // Parent itself must be a real directory.
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(normalized, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException("parent does not exist: " + normalized);
        }
        if (attrs.isSymbolicLink()) {
            throw new IOException("parent is a symlink: " + normalized);
        }
        if (!attrs.isDirectory()) {
            throw new IOException("parent is not a directory: " + normalized);
        }
    }

    private static void validateChainNoSymlink(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        // Walk each ancestor.
        for (Path p = absolute; p != null; p = p.getParent()) {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(p, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                throw new NoSuchFileException("chain element does not exist: " + p);
            } catch (AccessDeniedException e) {
                throw new AccessDeniedException("permission denied in chain: " + p);
            } catch (IOException e) {
                throw new IOException("I/O error validating chain element: " + p, e);
            }
            if (attrs.isSymbolicLink()) {
                throw new IOException("symlink/reparse in chain: " + p);
            }
            // The final element doesn't need to be a directory yet (it may
            // not exist), but every existing element must be a directory.
            if (!attrs.isDirectory() && !p.equals(absolute)) {
                throw new IOException("chain element is not a directory: " + p);
            }
        }
    }
}
