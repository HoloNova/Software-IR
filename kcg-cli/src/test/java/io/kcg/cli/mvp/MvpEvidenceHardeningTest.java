package io.kcg.cli.mvp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M4 hardening tests. These are deliberately test-only and exercise the
 * evidence boundary with real filesystem objects rather than mocks.
 */
class MvpEvidenceHardeningTest {

    @Test
    void unknownFileContainingSecretIsReportedAndSurvivesOwnedOnlyBreachCleanup() throws Exception {
        Path temp = Files.createTempDirectory("mvp-evidence-hardening-");
        try {
            Path root = temp.resolve("evidence");
            MvpEvidenceRoot owner = MvpEvidenceRoot.create(root);
            owner.register("evidence.json");
            MvpEvidence evidence = new MvpEvidence();
            evidence.put("status", "clean");
            owner.write(evidence, "evidence.json");
            Files.writeString(root.resolve("unknown.log"),
                    "unregistered password=secret-value\n", StandardCharsets.UTF_8);

            List<String> problems = owner.finalScan("secret-value");
            assertTrue(problems.stream().anyMatch(p -> p.startsWith("SECRET:unknown.log")),
                    "unknown files must be scanned before the unknown-file reconciliation failure");
            assertTrue(Files.exists(root.resolve("unknown.log"), LinkOption.NOFOLLOW_LINKS),
                    "credential breach cleanup must not delete an unregistered file");
        } finally {
            deleteTree(temp);
        }
    }

    @Test
    void evidenceWriteNeverOverwritesAnExistingTarget() throws Exception {
        Path temp = Files.createTempDirectory("mvp-evidence-hardening-");
        try {
            Path root = temp.resolve("evidence");
            MvpEvidenceRoot owner = MvpEvidenceRoot.create(root);
            owner.register("evidence.json");
            Files.writeString(root.resolve("evidence.json"), "caller-owned\n", StandardCharsets.UTF_8);
            MvpEvidence evidence = new MvpEvidence();
            evidence.put("status", "harness");

            assertThrows(Exception.class, () -> owner.write(evidence, "evidence.json"));
            assertTrue(Files.readString(root.resolve("evidence.json"), StandardCharsets.UTF_8)
                    .equals("caller-owned\n"), "existing evidence must not be clobbered");
        } finally {
            deleteTree(temp);
        }
    }

    @Test
    void missingFileKeyCannotMakeDifferentIdentityEqual() {
        MvpSupport.TreeSnapshot.Entry before = new MvpSupport.TreeSnapshot.Entry(
                "f", MvpSupport.TreeSnapshot.Kind.REGULAR_FILE, 1L, "a", "<none>", false);
        MvpSupport.TreeSnapshot.Entry after = new MvpSupport.TreeSnapshot.Entry(
                "f", MvpSupport.TreeSnapshot.Kind.REGULAR_FILE, 2L, "a", "<none>", false);
        assertFalse(before.sameIdentityAs(after),
                "identity comparison must not fail open when fileKey is unavailable");
    }

    @Test
    void percentEncodedSecretOutsideAKeyValuePairIsDetected() throws Exception {
        String secret = "p@ss word";
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        List<String> hits = MvpEvidence.scanBytes(
                ("opaque=" + encoded).getBytes(StandardCharsets.UTF_8), secret);
        assertTrue(!hits.isEmpty(), "percent-encoded registered secret must be detected");
    }

    @Test
    void unchangedIdentityTokenDoesNotBecomeAFalseMismatch() {
        MvpSupport.TreeSnapshot.Entry a = new MvpSupport.TreeSnapshot.Entry(
                "f", MvpSupport.TreeSnapshot.Kind.REGULAR_FILE, 1L, "a", "FALLBACK:1:2", false);
        MvpSupport.TreeSnapshot.Entry b = new MvpSupport.TreeSnapshot.Entry(
                "f", MvpSupport.TreeSnapshot.Kind.REGULAR_FILE, 1L, "a", "FALLBACK:1:2", false);
        assertTrue(a.sameIdentityAs(b));
        assertNotEquals(a.identity(), "<none>");
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                BasicFileAttributes attrs = Files.readAttributes(
                        child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isDirectory() && !attrs.isSymbolicLink()) {
                    deleteTree(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
        }
        Files.deleteIfExists(root);
    }
}
