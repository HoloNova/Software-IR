package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FailureDisposition;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests READ and PARSE stage failures: non-absolute paths, invalid UTF-8,
 * missing files, and invalid SIR — all must return structured Failure with
 * the correct stage and zero filesystem modifications.
 */
class ToolchainReadStageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void nonAbsoluteSourceFileFailsAtReadStage() throws Exception {
        Path source = temporaryDirectory.resolve("valid.sir");
        Files.writeString(source, "sir 0.1", StandardCharsets.UTF_8);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                Path.of("relative/path.sir"),
                SourceId.of("relative.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.READ, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-REQUEST")));
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void nonAbsoluteOutputRootFailsAtReadStage() throws Exception {
        Path source = temporaryDirectory.resolve("valid.sir").toAbsolutePath();
        Files.writeString(source, "sir 0.1", StandardCharsets.UTF_8);

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source,
                SourceId.of("valid.sir"),
                Path.of("relative/output"),
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.READ, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-REQUEST")));
    }

    @Test
    void invalidUtf8FailsAtReadStage() throws Exception {
        Path source = temporaryDirectory.resolve("bad-utf8.sir");
        // 0xFF is never valid as the first byte of a UTF-8 sequence.
        Files.write(source, new byte[]{(byte) 0xFF, (byte) 0xFE, 's', 'i', 'r'});
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("bad-utf8.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.READ, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-READ")));
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void missingFileFailsAtReadStage() {
        Path source = temporaryDirectory.resolve("nonexistent.sir").toAbsolutePath();
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source,
                SourceId.of("nonexistent.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.READ, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-APP-READ")));
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void invalidSirFailsAtParseWithoutCreatingOutputRoot() throws Exception {
        Path source = temporaryDirectory.resolve("invalid.sir");
        Files.writeString(source, "not valid SIR", StandardCharsets.UTF_8);
        Path outputRoot = temporaryDirectory.resolve("generated-project");

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("invalid.sir"),
                outputRoot.toAbsolutePath(),
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.PARSE, failure.failedStage());
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition());
        assertFalse(failure.diagnostics().isEmpty());
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void parseFailurePreservesOriginalErrorCodes() throws Exception {
        Path source = temporaryDirectory.resolve("invalid.sir");
        Files.writeString(source, "sir 0.1\nsoftware Broken {\n", StandardCharsets.UTF_8);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("invalid.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.PARSE, failure.failedStage());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-")),
                "diagnostics should carry original SIR-* codes: " + failure.diagnostics());
        for (ExecutionDiagnostic d : failure.diagnostics()) {
            assertEquals(ExecutionStage.PARSE, d.stage(),
                    "all diagnostics from parse failure should be annotated with PARSE stage");
        }
    }
}
