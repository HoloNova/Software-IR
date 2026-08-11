package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.source.SourceId;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GRAPH-stage failure injection. Verifies that when the ProjectGraph build
 * or validation fails, the Toolchain returns a Failure annotated with
 * {@link ExecutionStage#GRAPH} and {@link FailureDisposition#NO_CHANGES},
 * and that no output root or transaction directory is created on disk.
 *
 * <p>The test uses the package-private
 * {@link ToolchainApplication#ToolchainApplication(Function, Function)}
 * constructor to inject a graph step that always returns
 * {@link ProjectGraphAnalysis.Failure}. This is the only test seam; no
 * public hook is exposed for graph failure injection.
 *
 * <p>Fix #5: also verifies that a Graph {@link ProjectGraphAnalysis.Success}
 * carrying WARNING and INFO diagnostics has those diagnostics preserved in
 * the final {@link ToolchainResult.Success}, and that the disk transaction
 * still completes.
 */
class ToolchainGraphStageFailureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void graphFailureReturnsNoChangesAndCreatesNoOutputRoot() throws Exception {
        // Inject a graph step that always fails with one ERROR diagnostic.
        Function<io.kcg.sir.projectgraph.api.ProjectGraphInput, ProjectGraphAnalysis>
                failingGraphStep = input -> new ProjectGraphAnalysis.Failure(List.of(
                        ProjectGraphDiagnostic.error(
                                "SIR-GRAPH-TEST-001",
                                "injected graph failure")));

        ToolchainApplication application = new ToolchainApplication(
                model -> new io.kcg.sir.generator.springboot.api.GenerationResult.Success(
                        java.util.List.of()),
                failingGraphStep);

        Path source = copyCampusMarketSource();
        Path outputRoot = temporaryDirectory.resolve("graph-failure-out").toAbsolutePath();

        ToolchainResult result = application.execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("campus-market.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.GRAPH, failure.failedStage(),
                "failed stage must be GRAPH");
        assertEquals(FailureDisposition.NO_CHANGES, failure.disposition(),
                "disposition must be NO_CHANGES");
        assertTrue(failure.diagnostics().stream().anyMatch(ExecutionDiagnostic::isError),
                "must carry at least one ERROR diagnostic");
        assertTrue(failure.diagnostics().stream().anyMatch(d ->
                        d.code().equals("SIR-GRAPH-TEST-001")),
                "must carry the injected graph diagnostic code");
        assertFalse(Files.exists(outputRoot),
                "output root must not be created on GRAPH failure");
        // No transaction directory should exist either.
        try (java.util.stream.Stream<Path> entries = Files.list(temporaryDirectory)) {
            entries.forEach(p -> {
                String name = p.getFileName().toString();
                assertFalse(name.startsWith(".sir-tx-"),
                        "no transaction directory must be left behind: " + name);
                assertFalse(name.startsWith(".sir-bak-"),
                        "no backup directory must be left behind: " + name);
            });
        }
    }

    @Test
    void graphFailureDiagnosticsAreMappedWithGraphStage() throws Exception {
        // The graph diagnostic's code and message must be preserved; the
        // owning stage must be GRAPH.
        Function<io.kcg.sir.projectgraph.api.ProjectGraphInput, ProjectGraphAnalysis>
                failingGraphStep = input -> new ProjectGraphAnalysis.Failure(List.of(
                        ProjectGraphDiagnostic.error(
                                "SIR-GRAPH-NODE-001",
                                "duplicate node id")));

        ToolchainApplication application = new ToolchainApplication(
                model -> new io.kcg.sir.generator.springboot.api.GenerationResult.Success(
                        java.util.List.of()),
                failingGraphStep);

        Path source = copyCampusMarketSource();
        Path outputRoot = temporaryDirectory.resolve("graph-diag-out").toAbsolutePath();

        ToolchainResult result = application.execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("campus-market.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        assertEquals(ExecutionStage.GRAPH, failure.failedStage());
        ExecutionDiagnostic graphDiag = failure.diagnostics().stream()
                .filter(d -> d.code().equals("SIR-GRAPH-NODE-001"))
                .findFirst()
                .orElseThrow();
        assertEquals("duplicate node id", graphDiag.message());
        assertEquals(ExecutionStage.GRAPH, graphDiag.stage());
    }

    /**
     * Fix #5: when the graph step returns Success carrying WARNING and INFO
     * diagnostics, the Application must preserve both in the final
     * ToolchainResult.Success (mapped to ExecutionDiagnostic with stage=GRAPH)
     * and must still complete the disk transaction. The Application must not
     * rebuild or re-validate the graph after the Success is returned.
     */
    @Test
    void graphSuccessPreservesWarningAndInfoDiagnostics() throws Exception {
        // Inject a graph step that builds a real graph from the input and
        // returns Success with both a WARNING and an INFO diagnostic. The
        // Application must carry both into the final ToolchainResult.Success.
        Function<io.kcg.sir.projectgraph.api.ProjectGraphInput, ProjectGraphAnalysis>
                graphStepWithWarnings = input -> {
                    ProjectGraphAnalysis analysis = new ProjectGraphBuilder().build(input);
                    if (analysis instanceof ProjectGraphAnalysis.Success s) {
                        return new ProjectGraphAnalysis.Success(
                                s.graph(),
                                List.of(
                                        ProjectGraphDiagnostic.warning(
                                                "SIR-GRAPH-WARN-001", "injected graph warning"),
                                        ProjectGraphDiagnostic.info(
                                                "SIR-GRAPH-INFO-001", "injected graph info")));
                    }
                    return analysis;
                };

        ToolchainApplication application = new ToolchainApplication(
                model -> new io.kcg.sir.generator.springboot.api.GenerationResult.Success(
                        java.util.List.of()),
                graphStepWithWarnings);

        Path source = copyCampusMarketSource();
        Path outputRoot = temporaryDirectory.resolve("graph-success-out").toAbsolutePath();

        ToolchainResult result = application.execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("campus-market.sir"),
                outputRoot,
                ConflictPolicy.FAIL_IF_EXISTS));

        ToolchainResult.Success success = assertInstanceOf(ToolchainResult.Success.class, result,
                "Graph Success must propagate to ToolchainResult.Success: " + result);

        // WARNING diagnostic must be preserved with stage=GRAPH.
        assertTrue(success.diagnostics().stream().anyMatch(d ->
                        d.code().equals("SIR-GRAPH-WARN-001")
                                && d.severity() == ExecutionSeverity.WARNING
                                && d.stage() == ExecutionStage.GRAPH),
                "WARNING diagnostic must be preserved in final Success: "
                        + success.diagnostics());

        // INFO diagnostic must be preserved with stage=GRAPH.
        assertTrue(success.diagnostics().stream().anyMatch(d ->
                        d.code().equals("SIR-GRAPH-INFO-001")
                                && d.severity() == ExecutionSeverity.INFO
                                && d.stage() == ExecutionStage.GRAPH),
                "INFO diagnostic must be preserved in final Success: "
                        + success.diagnostics());

        // The disk transaction must still complete and create the output root.
        assertTrue(Files.exists(outputRoot),
                "disk transaction must succeed and create the output root");
    }

    private Path copyCampusMarketSource() throws Exception {
        Path source = temporaryDirectory.resolve("campus-market.sir");
        try (InputStream input = ToolchainGraphStageFailureTest.class.getResourceAsStream(
                "/valid/campus-market.sir")) {
            if (input == null) {
                throw new IllegalStateException("missing test resource: /valid/campus-market.sir");
            }
            Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
        }
        return source;
    }
}