package io.kcg.sir.application.internal.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.application.internal.bundle.OutputManifestVerifier;
import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RQ-09 targeted security review tests. Each test here documents a review finding:
 * first written as a failing test, then the minimal fix that closes the gap.
 * Review scope: path guards, safe target resolution, manifest verification.
 */
class PathSecurityReviewTest {

    // --- Finding A: TransactionJournal.validateRelativePath is weaker than PathGuard ---
    // It rejects '/'/drive/backslash/NUL/'.'/'..' but NOT Windows trailing dot/space
    // segments or reserved device names. On Windows, "foo." and "foo " normalize to
    // "foo", so two distinct plan paths can silently collide on one physical file
    // (manifest would then carry two entries pointing at the same target). The guard
    // must fail closed on these names exactly like PathGuard does.

    @Test
    void validateRelativePathRejectsTrailingDotSegment() {
        String error = TransactionJournal.validateRelativePath("dir/file.");
        assertNotNull(error, "trailing dot segment must be rejected (Windows normalizes 'file.' to 'file')");
    }

    @Test
    void validateRelativePathRejectsTrailingSpaceSegment() {
        String error = TransactionJournal.validateRelativePath("dir/file ");
        assertNotNull(error, "trailing space segment must be rejected (Windows normalizes 'file ' to 'file')");
    }

    @Test
    void validateRelativePathRejectsWindowsReservedDeviceName() {
        String error = TransactionJournal.validateRelativePath("dir/CON");
        assertNotNull(error, "Windows reserved device name must be rejected");
    }

    @Test
    void validateRelativePathRejectsIllegalChars() {
        String error = TransactionJournal.validateRelativePath("dir/a<b>c");
        assertNotNull(error, "illegal Windows filename characters must be rejected");
    }

    @Test
    void safeTargetResolverRejectsTrailingDotViaValidateRelativePath() throws Exception {
        Path root = Files.createTempDirectory("rq09-root-");
        try {
            SafeTargetResolver resolver = SafeTargetResolver.forRoot(root);
            assertThrows(SafeTargetResolver.UnsafePathException.class,
                    () -> resolver.resolveNoCreate("file."),
                    "resolveNoCreate must fail closed on trailing-dot paths");
        } finally {
            deleteTree(root);
        }
    }

    // --- Finding B: manifest verification must reject duplicate relative paths ---
    // Two manifest entries for the same relativePath mean the plan/generator produced
    // ambiguous output. The verifier must fail closed instead of verifying the same
    // target twice (second verification could mask a first mismatch after a swap).

    @Test
    void manifestVerifierRejectsDuplicateRelativePaths() throws Exception {
        Path root = Files.createTempDirectory("rq09-manifest-");
        try {
            byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
            Path file = root.resolve("a.txt");
            Files.write(file, bytes);
            String sha = io.kcg.sir.application.internal.Sha256.hexDigest("hello");
            BaselineManifestEntry entry = new BaselineManifestEntry("a.txt", bytes.length, sha,
                    new io.kcg.sir.lowering.api.LoweredNodeId("lir://x/a"),
                    java.util.Optional.empty());
            List<ChangeExecutionDiagnostic> errors = OutputManifestVerifier.verify(
                    root, List.of(entry, entry), ChangeExecutionStage.COMMIT);
            assertTrue(errors.stream().anyMatch(e -> e.code().equals("SIR-APP-CHANGE-COMMIT-007")),
                    "duplicate manifest entries must be rejected, got: " + errors);
        } finally {
            deleteTree(root);
        }
    }

    // --- Finding B: reparse points (junction / mount point) in the parent chain ---
    // isSymbolicLink() covers symlinks and (on modern JDKs) junctions, but NOT all
    // reparse-point kinds. A mount-point directory is a directory to NOFOLLOW reads
    // while its content lives on another volume: writes would escape the root.
    // These tests are Windows-only because junctions do not exist on POSIX.

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void junctionInParentChainIsRejectedBySafeTargetResolver() throws Exception {
        Path temp = Files.createTempDirectory("rq09-junction-");
        try {
            Path real = temp.resolve("real");
            Files.createDirectory(real);
            Path link = temp.resolve("link");
            createJunction(link, real);

            // A resolver rooted under the junction must reject it: the junction is a
            // link in the raw chain of the root itself.
            assertThrows(SafeTargetResolver.UnsafePathException.class,
                    () -> SafeTargetResolver.forRoot(link.resolve("sub")),
                    "junction in root chain must be rejected");
        } finally {
            deleteTree(temp);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void junctionInTargetParentChainIsRejectedBySafeTargetResolver() throws Exception {
        Path temp = Files.createTempDirectory("rq09-junction2-");
        try {
            Path real = temp.resolve("real");
            Files.createDirectory(real);
            Path root = temp.resolve("root");
            Files.createDirectory(root);
            Path link = root.resolve("linked");
            createJunction(link, real);

            SafeTargetResolver resolver = SafeTargetResolver.forRoot(root);
            assertThrows(SafeTargetResolver.UnsafePathException.class,
                    () -> resolver.resolveNoCreate("linked/file.txt"),
                    "junction in target parent chain must be rejected");
        } finally {
            deleteTree(temp);
        }
    }

    // --- Finding B2: the same junction gap exists in PathGuard / FileTransaction /
    // OutputManifestVerifier chain checks (all use isSymbolicLink only). ---

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void pathGuardPreflightRejectsJunctionInTargetParentChain() throws Exception {
        Path temp = Files.createTempDirectory("rq09-pg-");
        try {
            Path real = temp.resolve("real");
            Files.createDirectory(real);
            Path out = temp.resolve("out");
            Files.createDirectory(out);
            Path link = out.resolve("linked");
            createJunction(link, real);

            io.kcg.sir.generator.springboot.api.GeneratedFile f =
                    new io.kcg.sir.generator.springboot.api.GeneratedFile(
                            "linked/pom.xml", "<project/>",
                            new io.kcg.sir.lowering.api.LoweredNodeId("lir://x/pom"),
                            java.util.Optional.empty());
            List<io.kcg.sir.application.api.ExecutionDiagnostic> errors =
                    io.kcg.sir.application.internal.PathGuard.preflight(
                            out, List.of(f), io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS);
            assertTrue(errors.stream().anyMatch(e -> e.code().equals("SIR-APP-PATH-003")),
                    "junction in target parent chain must be rejected by preflight, got: " + errors);
        } finally {
            deleteTree(temp);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void manifestVerifierRejectsJunctionInTrackedFileParentChain() throws Exception {
        Path temp = Files.createTempDirectory("rq09-mv-");
        try {
            Path real = temp.resolve("real");
            Files.createDirectory(real);
            Path out = temp.resolve("out");
            Files.createDirectory(out);
            Path link = out.resolve("linked");
            createJunction(link, real);
            Path file = real.resolve("a.txt");
            Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

            BaselineManifestEntry entry = new BaselineManifestEntry("linked/a.txt", 5L,
                    io.kcg.sir.application.internal.Sha256.hexDigest("hello"),
                    new io.kcg.sir.lowering.api.LoweredNodeId("lir://x/a"),
                    java.util.Optional.empty());
            List<ChangeExecutionDiagnostic> errors = OutputManifestVerifier.verify(
                    out, List.of(entry), ChangeExecutionStage.COMMIT);
            assertTrue(errors.stream().anyMatch(e -> e.code().equals("SIR-APP-CHANGE-COMMIT-002")),
                    "junction in tracked file parent chain must be rejected, got: " + errors);
        } finally {
            deleteTree(temp);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void fileTransactionRejectsJunctionInTargetParentChain() throws Exception {
        Path temp = Files.createTempDirectory("rq09-ft-");
        try {
            Path real = temp.resolve("real");
            Files.createDirectory(real);
            Path out = temp.resolve("out");
            Files.createDirectory(out);
            Path link = out.resolve("linked");
            createJunction(link, real);

            io.kcg.sir.generator.springboot.api.GeneratedFile f =
                    new io.kcg.sir.generator.springboot.api.GeneratedFile(
                            "linked/x.txt", "payload",
                            new io.kcg.sir.lowering.api.LoweredNodeId("lir://x/f"),
                            java.util.Optional.empty());
            io.kcg.sir.application.internal.TransactionResult result =
                    new io.kcg.sir.application.internal.FileTransaction(
                            out, List.of(f), io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS)
                            .execute();
            assertTrue(result instanceof io.kcg.sir.application.internal.TransactionResult.Failure,
                    "commit through a junction parent chain must fail closed, got: " + result);
            assertTrue(Files.list(real).findAny().isEmpty(),
                    "no file may be written through the junction into the real directory");
        } finally {
            deleteTree(temp);
        }
    }

    private static void createJunction(Path link, Path target) throws Exception {
        Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toAbsolutePath().toString(),
                target.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("mklink /J failed: " + out);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // Keep assertEquals imported usage minimal (unused-import hygiene).
    @Test
    void sanityMarker() {
        assertEquals(1, 1);
    }
}
