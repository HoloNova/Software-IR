package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FailureDisposition;
import io.kcg.sir.application.api.FileAction;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conflict policy tests: FAIL_IF_EXISTS (default) rejects any existing target
 * with zero modifications; REPLACE_EXISTING replaces regular files and marks
 * them REPLACED while leaving unrelated files untouched.
 */
class ToolchainConflictPolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void failIfExistsRejectsWhenAnyTargetExists() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(outputRoot);
        // Pre-create one target file that the generator will also produce.
        Path existingPom = outputRoot.resolve("pom.xml");
        String originalContent = "<original/>";
        Files.writeString(existingPom, originalContent, StandardCharsets.UTF_8);

        ToolchainResult result = new io.kcg.sir.application.api.ToolchainApplication().execute(
                new ToolchainRequest(
                        source,
                        io.kcg.sir.source.SourceId.of("campus-market.sir"),
                        outputRoot,
                        ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.PREFLIGHT, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-CONFLICT")),
                "expected SIR-APP-CONFLICT diagnostic: " + failure.diagnostics());

        // Old file must be unchanged.
        assertEquals(originalContent, Files.readString(existingPom));
    }

    @Test
    void replaceExistingReplacesRegularFileAndMarksReplaced() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(outputRoot);
        Path existingPom = outputRoot.resolve("pom.xml");
        String originalContent = "<original/>";
        Files.writeString(existingPom, originalContent, StandardCharsets.UTF_8);

        ToolchainResult.Success success = ApplicationTestSupport.runCampusMarket(
                source, outputRoot, ConflictPolicy.REPLACE_EXISTING);

        // The pom.xml must be replaced with generated content.
        String onDisk = Files.readString(existingPom);
        assertFalse(onDisk.equals(originalContent), "pom.xml must have been replaced");

        boolean foundReplaced = false;
        for (AppliedFile f : success.manifest().files()) {
            if (f.relativePath().equals("pom.xml")) {
                assertEquals(FileAction.REPLACED, f.action(),
                        "pom.xml must be marked REPLACED");
                foundReplaced = true;
            } else {
                assertEquals(FileAction.CREATED, f.action(),
                        "non-existing files must be CREATED: " + f.relativePath());
            }
        }
        assertTrue(foundReplaced, "pom.xml must be in manifest");
    }

    @Test
    void replaceExistingLeavesUnrelatedFilesUntouched() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(outputRoot);
        Path existingPom = outputRoot.resolve("pom.xml");
        Files.writeString(existingPom, "<original/>", StandardCharsets.UTF_8);
        // Create an unrelated file that the generator does NOT produce.
        Path unrelated = outputRoot.resolve("README.md");
        String unrelatedContent = "# My Project";
        Files.writeString(unrelated, unrelatedContent, StandardCharsets.UTF_8);

        ApplicationTestSupport.runCampusMarket(source, outputRoot, ConflictPolicy.REPLACE_EXISTING);

        assertEquals(unrelatedContent, Files.readString(unrelated),
                "unrelated file must be unchanged");
        assertTrue(Files.exists(unrelated), "unrelated file must still exist");
    }

    @Test
    void replaceExistingRejectsDirectoryAtTarget() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();
        Files.createDirectories(outputRoot);
        // Create a directory where pom.xml would go.
        Files.createDirectories(outputRoot.resolve("pom.xml"));

        ToolchainResult result = new io.kcg.sir.application.api.ToolchainApplication().execute(
                new ToolchainRequest(
                        source,
                        io.kcg.sir.source.SourceId.of("campus-market.sir"),
                        outputRoot,
                        ConflictPolicy.REPLACE_EXISTING));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.PREFLIGHT, failure.failedStage());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-CONFLICT")),
                "directory at target must be rejected: " + failure.diagnostics());
    }
}
