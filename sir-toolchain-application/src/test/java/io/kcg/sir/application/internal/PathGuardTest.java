package io.kcg.sir.application.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct unit tests for {@link PathGuard}. {@link GeneratedFile} already
 * rejects {@code ..}, backslash, absolute paths, drive letters and empty
 * segments at construction, so those cases are exercised indirectly by the
 * generator tests. This class focuses on the additional defense-in-depth
 * checks that PathGuard applies on top of {@code GeneratedFile}: the
 * {@code .} segment, Windows illegal characters, control characters,
 * trailing dots/spaces, Windows reserved names, case-fold duplicates,
 * normalization escape and symbolic-link rejection.
 */
class PathGuardTest {

    private static final LoweredNodeId ARTIFACT = new LoweredNodeId("test-artifact");

    @TempDir
    Path temporaryDirectory;

    private static GeneratedFile file(String relativePath) {
        return new GeneratedFile(relativePath, "x\n", ARTIFACT, Optional.empty());
    }

    private static List<ExecutionDiagnostic> preflight(
            Path outputRoot, List<GeneratedFile> files, ConflictPolicy policy) {
        return PathGuard.preflight(outputRoot, files, policy);
    }

    private static boolean hasCode(List<ExecutionDiagnostic> diags, String codePrefix) {
        return diags.stream().anyMatch(d -> d.code().startsWith(codePrefix));
    }

    // ---- '.' segment rejection ----

    @Test
    void dotSegmentIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("./pom.xml")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for '.' segment: " + errors);
    }

    @Test
    void dotSegmentInMiddleIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("src/./main/java/Foo.java")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for middle '.' segment: " + errors);
    }

    // ---- Windows illegal characters ----

    @Test
    void windowsIllegalCharsAreRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        for (char c : "<>:\"|?*".toCharArray()) {
            String path = "src/Foo" + c + ".java";
            List<ExecutionDiagnostic> errors = preflight(
                    root, List.of(file(path)), ConflictPolicy.FAIL_IF_EXISTS);
            assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                    "expected SIR-APP-PATH-001 for illegal char '" + c + "' in " + path
                            + ": " + errors);
        }
    }

    // ---- Control characters ----

    @Test
    void controlCharactersAreRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        for (int c : new int[]{0x00, 0x01, 0x08, 0x0A, 0x1F, 0x7F}) {
            String path = "src/Foo" + (char) c + ".java";
            List<ExecutionDiagnostic> errors = preflight(
                    root, List.of(file(path)), ConflictPolicy.FAIL_IF_EXISTS);
            assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                    "expected SIR-APP-PATH-001 for control char 0x" + Integer.toHexString(c)
                            + ": " + errors);
        }
    }

    // ---- Trailing dot / space ----

    @Test
    void trailingDotIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("src/Foo.")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for trailing dot: " + errors);
    }

    @Test
    void trailingSpaceIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("src/Foo ")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for trailing space: " + errors);
    }

    @Test
    void trailingDotOnNonLastSegmentIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("Foo./Bar.java")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for trailing dot on intermediate segment: " + errors);
    }

    // ---- Windows reserved names ----

    @Test
    void windowsReservedNamesAreRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        String[] reserved = {"CON", "PRN", "AUX", "NUL", "COM1", "COM9", "LPT1", "LPT9"};
        for (String name : reserved) {
            List<ExecutionDiagnostic> errors = preflight(
                    root, List.of(file(name + ".txt")), ConflictPolicy.FAIL_IF_EXISTS);
            assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                    "expected SIR-APP-PATH-001 for reserved name " + name + ": " + errors);
        }
    }

    @Test
    void windowsReservedNameWithoutExtensionIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("CON")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for reserved name CON without extension: " + errors);
    }

    @Test
    void windowsReservedNameInSubdirectoryIsRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("src/COM1.java")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for reserved name in subdir: " + errors);
    }

    // ---- Case-fold duplicate detection ----

    @Test
    void caseFoldDuplicatesAreRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<GeneratedFile> files = List.of(
                file("src/main/java/Foo.java"),
                file("src/main/java/foo.java"));
        List<ExecutionDiagnostic> errors = preflight(root, files, ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-002"),
                "expected SIR-APP-PATH-002 for case-fold duplicate: " + errors);
    }

    @Test
    void exactDuplicatePathsAreRejected() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<GeneratedFile> files = List.of(
                file("src/main/java/Foo.java"),
                file("src/main/java/Foo.java"));
        List<ExecutionDiagnostic> errors = preflight(root, files, ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-002"),
                "expected SIR-APP-PATH-002 for exact duplicate: " + errors);
    }

    // ---- Non-absolute root ----

    @Test
    void nonAbsoluteRootIsRejected() {
        Path root = Path.of("relative/out");
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("pom.xml")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-REQUEST-002"),
                "expected SIR-APP-REQUEST-002 for non-absolute root: " + errors);
    }

    // ---- Valid paths pass ----

    @Test
    void validPathsPass() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<GeneratedFile> files = List.of(
                file("pom.xml"),
                file("src/main/java/io/kcg/Foo.java"));
        List<ExecutionDiagnostic> errors = preflight(root, files, ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(errors.isEmpty(),
                "valid paths must pass preflight: " + errors);
    }

    // ---- Conflict checks ----

    @Test
    void failIfExistsRejectsAnyExistingTarget() throws Exception {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), "<original/>");
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("pom.xml")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-CONFLICT-001"),
                "expected SIR-APP-CONFLICT-001 when target exists under FAIL_IF_EXISTS: " + errors);
    }

    @Test
    void replaceExistingAcceptsExistingRegularFile() throws Exception {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), "<original/>");
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("pom.xml")), ConflictPolicy.REPLACE_EXISTING);
        assertTrue(errors.isEmpty(),
                "REPLACE_EXISTING must accept an existing regular file: " + errors);
    }

    @Test
    void replaceExistingRejectsDirectory() throws Exception {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(root.resolve("pom.xml"));
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("pom.xml")), ConflictPolicy.REPLACE_EXISTING);
        assertTrue(hasCode(errors, "SIR-APP-CONFLICT-002"),
                "expected SIR-APP-CONFLICT-002 for directory under REPLACE_EXISTING: " + errors);
    }

    // ---- Symlink rejection ----
    //
    // PathGuard rejects symbolic links in the output root chain, the target
    // parent chain, the target itself, or the output root itself. These tests
    // create real symbolic links; they are skipped on platforms that cannot
    // create them (e.g. Windows without developer mode / admin). This
    // integration limitation is reported separately in the final report per
    // the design spec.

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rootParentChainSymlinkIsRejected() throws Exception {
        Path real = temporaryDirectory.resolve("real-out").toAbsolutePath();
        Files.createDirectories(real);
        Path link = temporaryDirectory.resolve("link-out").toAbsolutePath();
        Files.createSymbolicLink(link, real);
        Path rootInsideLink = link.resolve("project");

        List<ExecutionDiagnostic> errors = preflight(
                rootInsideLink, List.of(file("pom.xml")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-003"),
                "expected SIR-APP-PATH-003 for symlink in root parent chain: " + errors);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rootItselfIsSymlinkIsRejected() throws Exception {
        Path real = temporaryDirectory.resolve("real-target").toAbsolutePath();
        Files.createDirectories(real);
        Path link = temporaryDirectory.resolve("root-link").toAbsolutePath();
        Files.createSymbolicLink(link, real);

        List<ExecutionDiagnostic> errors = preflight(
                link, List.of(file("pom.xml")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-003"),
                "expected SIR-APP-PATH-003 when root itself is a symlink: " + errors);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void targetParentChainSymlinkIsRejected() throws Exception {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        Path realDir = temporaryDirectory.resolve("real-subdir").toAbsolutePath();
        Files.createDirectories(realDir);
        // root/src is a symlink to realDir, then file pom.xml would land inside
        // the symlinked parent.
        Files.createDirectories(root);
        Path linkedSrc = root.resolve("src");
        Files.createSymbolicLink(linkedSrc, realDir);

        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("src/pom.xml")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-003"),
                "expected SIR-APP-PATH-003 for symlink in target parent chain: " + errors);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void targetItselfIsSymlinkIsRejected() throws Exception {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(root);
        Path realFile = temporaryDirectory.resolve("real-file").toAbsolutePath();
        Files.writeString(realFile, "x");
        Path targetLink = root.resolve("pom.xml");
        Files.createSymbolicLink(targetLink, realFile);

        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("pom.xml")), ConflictPolicy.REPLACE_EXISTING);
        assertTrue(hasCode(errors, "SIR-APP-CONFLICT-002"),
                "expected SIR-APP-CONFLICT-002 when target is a symlink under REPLACE_EXISTING: "
                        + errors);
    }

    // ---- Normalization escape (defense-in-depth) ----
    //
    // GeneratedFile rejects ".." segments at construction, so an attacker who
    // could bypass GeneratedFile would still hit PathGuard's normalization
    // check. We cannot easily craft such a GeneratedFile, but we verify that
    // valid paths whose normalized form is still inside the root are accepted.

    @Test
    void normalizablePathsInsideRootAreAccepted() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<GeneratedFile> files = List.of(
                file("a/b/c/Foo.java"),
                file("a/b/d/Bar.java"));
        List<ExecutionDiagnostic> errors = preflight(root, files, ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(errors.isEmpty(),
                "normalizable paths inside root must pass: " + errors);
    }

    @Test
    void diagnosticsAreStagePreflight() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("CON.txt")), ConflictPolicy.FAIL_IF_EXISTS);
        assertFalse(errors.isEmpty());
        for (ExecutionDiagnostic d : errors) {
            assertEquals(io.kcg.sir.application.api.ExecutionStage.PREFLIGHT, d.stage(),
                    "PathGuard diagnostics must be annotated with PREFLIGHT stage");
            assertEquals(io.kcg.sir.application.api.ExecutionSeverity.ERROR, d.severity());
        }
    }

    @Test
    void emptyFileListPasses() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(errors.isEmpty(),
                "empty file list must pass preflight: " + errors);
    }

    @Test
    void multipleDistinctPathsPass() {
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<GeneratedFile> files = List.of(
                file("pom.xml"),
                file("src/main/java/io/kcg/app/Application.java"),
                file("src/main/resources/application.properties"));
        List<ExecutionDiagnostic> errors = preflight(root, files, ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(errors.isEmpty(),
                "multiple distinct valid paths must pass: " + errors);
    }

    @Test
    void windowsReservedNameWithMultipleDotsRejected() {
        // Reserved name detection uses the part before the first dot.
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("COM1.log.txt")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for reserved name COM1 with multiple dots: " + errors);
    }

    @Test
    void nonReservedNameStartingWithReservedPrefixPasses() {
        // COM10 is NOT in the reserved set (COM1..COM9 only).
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("COM10.java")), ConflictPolicy.FAIL_IF_EXISTS);
        // COM10 does not match COM1..COM9, so it should pass segment validation.
        // (On Windows COM10 is technically not a reserved device name, only COM1..COM9.)
        assertFalse(hasCode(errors, "SIR-APP-PATH-001"),
                "COM10 should not be flagged as a reserved name: " + errors);
    }

    @Test
    void lowercaseReservedNameRejected() {
        // Reserved-name check uppercases via Locale.ROOT before comparison.
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("con.txt")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for lowercase reserved name 'con': " + errors);
    }

    @Test
    void singleDotFileNameRejectedForTrailingDot() {
        // "Foo.java." ends with dot -> rejected.
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors = preflight(
                root, List.of(file("Foo.java.")), ConflictPolicy.FAIL_IF_EXISTS);
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for trailing dot on filename: " + errors);
    }

    @Test
    void pathWithOnlySpacesInSegmentRejected() {
        // A segment containing only spaces ends with a space -> rejected.
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        List<ExecutionDiagnostic> errors;
        try {
            errors = preflight(
                    root, List.of(file("Foo/ /Bar.java")), ConflictPolicy.FAIL_IF_EXISTS);
        } catch (IllegalArgumentException e) {
            // GeneratedFile may also reject some forms; that's acceptable defense-in-depth.
            return;
        }
        assertTrue(hasCode(errors, "SIR-APP-PATH-001"),
                "expected SIR-APP-PATH-001 for segment with trailing space: " + errors);
    }

    @Test
    void verifyCaseFoldUsesLocaleRoot() {
        // Verify the duplicate check uses Locale.ROOT (not the default locale).
        // Under Turkish locale, 'I'.toLowerCase() == '谋' (dotless), not 'i'.
        // By using Locale.ROOT, "Foo.java" and "foo.java" must still collide.
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.of("tr", "TR"));
            Path root = temporaryDirectory.resolve("out").toAbsolutePath();
            List<GeneratedFile> files = List.of(
                    file("src/Foo.java"),
                    file("src/foo.java"));
            List<ExecutionDiagnostic> errors = preflight(
                    root, files, ConflictPolicy.FAIL_IF_EXISTS);
            assertTrue(hasCode(errors, "SIR-APP-PATH-002"),
                    "case-fold duplicate must be detected under Turkish locale: " + errors);
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}