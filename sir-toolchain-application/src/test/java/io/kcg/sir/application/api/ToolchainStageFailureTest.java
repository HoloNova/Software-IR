package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GenerationDiagnostic;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.source.SourceId;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainStageFailureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parseFailureIsStructuredKeepsTheOriginalCodesAndCreatesNoOutput() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("sources-parse"));
        Files.writeString(source.resolve("project.sir"), "sir 0.3\nsoftware Broken {\n");
        Path outputRoot = temporaryDirectory.resolve("parse-output").toAbsolutePath();

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, new ToolchainApplication().execute(
                new ToolchainRequest(source.toAbsolutePath(), SourceId.of("project.sir"), outputRoot)));

        assertEquals(ExecutionStage.PARSE, failure.failedStage());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.code().startsWith("SIR-")), failure.diagnostics().toString());
        assertTrue(failure.diagnostics().stream().allMatch(d -> d.stage() == ExecutionStage.PARSE), failure.diagnostics().toString());
        assertFalse(Files.exists(outputRoot));
    }

    @Test
    void semanticFailureIsStructuredAndCreatesNoOutput() throws Exception {
        assertStageFailure(new ToolchainApplication(),
                "/invalid/semantic-unresolved-name.sir", ExecutionStage.SEMANTIC);
    }

    @Test
    void loweringFailureIsStructuredAndCreatesNoOutput() throws Exception {
        assertStageFailure(new ToolchainApplication(),
                "/invalid/lowering-unsupported-any.sir", ExecutionStage.LOWERING);
    }

    @Test
    void generationFailureIsStructuredAndCreatesNoOutput() throws Exception {
        ToolchainApplication application = new ToolchainApplication(model ->
                new GenerationResult.Failure(List.of(GenerationDiagnostic.of(
                        "SIR-GEN-TEST-001", "injected generation failure"))));
        ToolchainResult.Failure failure = assertStageFailure(application,
                "/valid/campus-market.sir", ExecutionStage.GENERATION);
        assertTrue(failure.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("SIR-GEN-TEST-001")));
    }

    private ToolchainResult.Failure assertStageFailure(
            ToolchainApplication application, String resource, ExecutionStage expectedStage)
            throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("sources-" + expectedStage.name()));
        try (InputStream input = ToolchainStageFailureTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            Files.copy(input, source.resolve("project.sir"), StandardCopyOption.REPLACE_EXISTING);
        }
        Path outputRoot = temporaryDirectory.resolve(expectedStage.name()).toAbsolutePath();
        ToolchainResult result = application.execute(new ToolchainRequest(
                source.toAbsolutePath(), SourceId.of("project.sir"), outputRoot));

        ToolchainResult.Failure failure =
                assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(expectedStage, failure.failedStage());
        assertTrue(failure.diagnostics().stream().anyMatch(ExecutionDiagnostic::isError));
        assertFalse(Files.exists(outputRoot));
        return failure;
    }
}
