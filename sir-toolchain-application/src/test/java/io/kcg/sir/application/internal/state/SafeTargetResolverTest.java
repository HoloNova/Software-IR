package io.kcg.sir.application.internal.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.internal.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SafeTargetResolver} covering the five required
 * symlink-safe scenarios from the rework task:
 *
 * <ol>
 *   <li>outputRoot parent symlink;</li>
 *   <li>{@code link/..} raw chain attack;</li>
 *   <li>target symlink;</li>
 *   <li>pre-recovery parent symlink insertion;</li>
 *   <li>externally replaced target (byte mismatch).</li>
 * </ol>
 *
 * <p>Each test verifies that the resolver throws
 * {@link SafeTargetResolver.UnsafePathException} and that no destructive
 * filesystem operation occurs. Symlink tests use the same runtime
 * capability probe as {@code ChangeApplyCrashWindowTest}: if
 * {@code Files.createSymbolicLink} throws, the test returns (the
 * capability is not available on this platform).
 */
class SafeTargetResolverTest {

    @TempDir
    Path temporaryDirectory;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Attempt to create a symbolic link; return false if unsupported. */
    private boolean tryCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static byte[] writeRegularFile(Path file, String content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return bytes;
    }

    // ------------------------------------------------------------------
    // 1. outputRoot parent symlink
    // ------------------------------------------------------------------

    /**
     * A parent directory of outputRoot is a symlink. forRoot must reject
     * the root because the raw absolute chain contains a symlink.
     */
    @Test
    void parentSymlinkOfOutputRootRejected() throws IOException {
        // real/ is the real directory; link/ -> real/; outputRoot = link/sub
        Path realDir = temporaryDirectory.resolve("real").toAbsolutePath();
        Path linkDir = temporaryDirectory.resolve("link").toAbsolutePath();
        Path outputRoot = linkDir.resolve("sub");
        Files.createDirectories(realDir);
        if (!tryCreateSymlink(linkDir, realDir)) {
            return; // symlink not supported
        }
        Files.createDirectory(outputRoot);

        SafeTargetResolver.UnsafePathException ex = assertThrows(
                SafeTargetResolver.UnsafePathException.class,
                () -> SafeTargetResolver.forRoot(outputRoot));
        assertTrue(ex.getMessage().contains("symlink"),
                "must mention symlink: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 2. link/.. raw chain attack
    // ------------------------------------------------------------------

    /**
     * The classic {@code link/..} attack: outputRoot is expressed as
     * {@code symlink/..} which normalizes to the parent of the symlink
     * target. The raw chain traverses the symlink and must be rejected
     * even though the normalized path is safe.
     */
    @Test
    void linkDotDotRawChainAttackRejected() throws IOException {
        // real-outer/ contains real-inner/ and target-file.txt
        Path realOuter = temporaryDirectory.resolve("real-outer").toAbsolutePath();
        Path realInner = realOuter.resolve("real-inner");
        Files.createDirectories(realInner);
        writeRegularFile(realInner.resolve("target-file.txt"), "safe-content");

        // link/ -> real-outer/real-inner/
        Path linkDir = temporaryDirectory.resolve("link").toAbsolutePath();
        if (!tryCreateSymlink(linkDir, realInner)) {
            return; // symlink not supported
        }

        // outputRoot = link/.. which normalizes to real-outer/
        // The raw chain traverses link (a symlink) before normalization.
        Path outputRoot = linkDir.resolve("..");

        SafeTargetResolver.UnsafePathException ex = assertThrows(
                SafeTargetResolver.UnsafePathException.class,
                () -> SafeTargetResolver.forRoot(outputRoot));
        assertTrue(ex.getMessage().contains("symlink"),
                "must detect symlink in raw chain: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 3. target symlink
    // ------------------------------------------------------------------

    /**
     * The target file (resolved under outputRoot) is itself a symlink.
     * {@code resolveExistingFile} must reject it.
     */
    @Test
    void targetSymlinkRejected() throws Exception {
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(outputRoot);

        // Create a real file and a symlink pointing to it.
        Path realFile = outputRoot.resolve("real.txt");
        writeRegularFile(realFile, "real");
        Path linkFile = outputRoot.resolve("link.txt");
        if (!tryCreateSymlink(linkFile, realFile)) {
            return; // symlink not supported
        }

        SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);
        SafeTargetResolver.UnsafePathException ex = assertThrows(
                SafeTargetResolver.UnsafePathException.class,
                () -> resolver.resolveExistingFile("link.txt"));
        assertTrue(ex.getMessage().contains("symlink"),
                "must reject target symlink: " + ex.getMessage());
    }

    /**
     * A symlink is inserted into a parent directory of a target file
     * <em>after</em> the resolver was constructed but before resolve is
     * called. The resolver must detect the symlink in the parent chain
     * at resolve time.
     */
    @Test
    void parentSymlinkInsertedBeforeResolveRejected() throws Exception {
        // outputRoot/a/b/file.txt — initially all real directories.
        Path outputRoot = temporaryDirectory.resolve("out2").toAbsolutePath();
        Path parentA = outputRoot.resolve("a");
        Path parentB = parentA.resolve("b");
        Files.createDirectories(parentB);
        writeRegularFile(parentB.resolve("file.txt"), "content");

        SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);

        // Now replace parentA (a real directory) with a symlink to
        // another directory. The resolver must catch this at resolve.
        Path fakeDir = temporaryDirectory.resolve("fake-a").toAbsolutePath();
        Files.createDirectories(fakeDir);
        // Recreate parentA as a symlink.
        // First, delete the real parentA (and its children).
        deleteRecursively(parentA);
        if (!tryCreateSymlink(parentA, fakeDir)) {
            return; // symlink not supported
        }

        SafeTargetResolver.UnsafePathException ex = assertThrows(
                SafeTargetResolver.UnsafePathException.class,
                () -> resolver.resolveNoCreate("a/b/file.txt"));
        assertTrue(ex.getMessage().contains("symlink")
                        || ex.getMessage().contains("not a directory")
                        || ex.getMessage().contains("escapes"),
                "must detect parent symlink at resolve: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 4. externally replaced target (byte mismatch)
    // ------------------------------------------------------------------

    /**
     * Target file is externally replaced with different bytes after the
     * resolver was constructed. {@code resolveAndVerifyBytes} must reject
     * the mismatch and return no path.
     */
    @Test
    void externallyReplacedTargetByteMismatchRejected() throws Exception {
        Path outputRoot = temporaryDirectory.resolve("out3").toAbsolutePath();
        Files.createDirectories(outputRoot);

        Path target = outputRoot.resolve("file.txt");
        byte[] original = writeRegularFile(target, "original-content");
        String originalSha = Sha256.hexDigest(original);

        // Externally overwrite with different bytes.
        byte[] external = writeRegularFile(target, "external-different-bytes");
        assertFalse(originalSha.equals(Sha256.hexDigest(external)),
                "test precondition: external bytes must differ");

        SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);
        SafeTargetResolver.UnsafePathException ex = assertThrows(
                SafeTargetResolver.UnsafePathException.class,
                () -> resolver.resolveAndVerifyBytes(
                        "file.txt", original.length, originalSha));
        assertTrue(ex.getMessage().contains("mismatch"),
                "must report byte mismatch: " + ex.getMessage());

        // The external file must still be on disk (not deleted).
        assertTrue(Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                "external file must not be deleted");
        assertEquals(new String(external, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8),
                "external file bytes must be preserved");
    }

    // ------------------------------------------------------------------
    // 5. happy path: valid root and target
    // ------------------------------------------------------------------

    /**
     * A valid outputRoot with a regular target file resolves and verifies
     * successfully.
     */
    @Test
    void validRootAndTargetResolves() throws Exception {
        Path outputRoot = temporaryDirectory.resolve("valid").toAbsolutePath();
        Files.createDirectories(outputRoot);
        Path target = outputRoot.resolve("nested/path/file.txt");
        byte[] content = writeRegularFile(target, "valid");

        SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);
        Path resolved = resolver.resolveAndVerifyBytes(
                "nested/path/file.txt", content.length, Sha256.hexDigest(content));
        assertEquals(target.toAbsolutePath().normalize(), resolved);
    }

    /**
     * {@code resolve} rejects a relative path that escapes the root via
     * {@code ..}.
     */
    @Test
    void pathEscapeRejected() throws Exception {
        Path outputRoot = temporaryDirectory.resolve("escape").toAbsolutePath();
        Files.createDirectories(outputRoot);

        SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);
        SafeTargetResolver.UnsafePathException ex = assertThrows(
                SafeTargetResolver.UnsafePathException.class,
                () -> resolver.resolveNoCreate("../outside.txt"));
        assertTrue(ex.getMessage().contains("escapes root")
                        || ex.getMessage().contains("'..'"),
                "must reject path escape: " + ex.getMessage());
    }

    /**
     * {@code resolveExistingFile} rejects a non-existent target.
     */
    @Test
    void nonExistentTargetRejected() throws Exception {
        Path outputRoot = temporaryDirectory.resolve("ne").toAbsolutePath();
        Files.createDirectories(outputRoot);

        SafeTargetResolver resolver = SafeTargetResolver.forRoot(outputRoot);
        assertThrows(SafeTargetResolver.UnsafePathException.class,
                () -> resolver.resolveExistingFile("missing.txt"));
    }

    /**
     * {@code forRoot} rejects a non-directory root.
     */
    @Test
    void nonDirectoryRootRejected() throws Exception {
        Path file = temporaryDirectory.resolve("not-a-dir.txt").toAbsolutePath();
        writeRegularFile(file, "file");
        assertThrows(SafeTargetResolver.UnsafePathException.class,
                () -> SafeTargetResolver.forRoot(file));
    }

    /**
     * {@code forRoot} rejects a non-existent root.
     */
    @Test
    void nonExistentRootRejected() {
        Path missing = temporaryDirectory.resolve("does-not-exist").toAbsolutePath();
        assertThrows(SafeTargetResolver.UnsafePathException.class,
                () -> SafeTargetResolver.forRoot(missing));
    }

    // ------------------------------------------------------------------
    // Internal helper
    // ------------------------------------------------------------------

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}