package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionManifest;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.api.FailureDisposition;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that every public collection exposed by the application API is
 * unmodifiable: manifest files, Success diagnostics, Failure diagnostics, and
 * the AppliedFile/SymbolId optionals are defensive copies.
 */
class ToolchainManifestImmutabilityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void manifestFilesAreUnmodifiable() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ExecutionManifest manifest = success.manifest();
        List<AppliedFile> files = manifest.files();
        assertThrows(UnsupportedOperationException.class, () -> files.add(null),
                "manifest.files() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class, () -> files.clear(),
                "manifest.files() must be unmodifiable");
        if (!files.isEmpty()) {
            assertThrows(UnsupportedOperationException.class,
                    () -> files.remove(0),
                    "manifest.files() must be unmodifiable");
        }
    }

    @Test
    void successDiagnosticsAreUnmodifiable() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        List<ExecutionDiagnostic> diagnostics = success.diagnostics();
        assertThrows(UnsupportedOperationException.class, () -> diagnostics.add(null),
                "Success.diagnostics() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class, () -> diagnostics.clear(),
                "Success.diagnostics() must be unmodifiable");
    }

    @Test
    void failureDiagnosticsAreUnmodifiable() throws Exception {
        Path source = temporaryDirectory.resolve("invalid.sir");
        Files.writeString(source, "not valid SIR", StandardCharsets.UTF_8);
        Path outputRoot = temporaryDirectory.resolve("out").toAbsolutePath();

        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(),
                SourceId.of("invalid.sir"),
                outputRoot,
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS));
        ToolchainResult.Failure failure = assertInstanceOf(ToolchainResult.Failure.class, result);
        List<ExecutionDiagnostic> diagnostics = failure.diagnostics();
        assertThrows(UnsupportedOperationException.class, () -> diagnostics.add(null),
                "Failure.diagnostics() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class, () -> diagnostics.clear(),
                "Failure.diagnostics() must be unmodifiable");
    }

    @Test
    void manifestFilesAreDefensiveCopies() throws Exception {
        // Build a manifest with a mutable input list and verify mutations to
        // the input list do NOT affect the manifest's stored list.
        java.util.ArrayList<AppliedFile> input = new java.util.ArrayList<>();
        Path root = temporaryDirectory.resolve("out").toAbsolutePath();
        io.kcg.sir.lowering.api.LoweredNodeId artifact =
                new io.kcg.sir.lowering.api.LoweredNodeId("test");
        input.add(new AppliedFile(
                "pom.xml",
                io.kcg.sir.application.api.FileAction.CREATED,
                2L,
                "0".repeat(64),
                artifact,
                java.util.Optional.empty()));
        ExecutionManifest manifest = new ExecutionManifest(
                root,
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS,
                input);
        // Mutate the original input list.
        input.clear();
        assertEquals(1, manifest.files().size(),
                "manifest must hold a defensive copy of the input list");
    }

    @Test
    void successBuiltFromMutableDiagnosticsIsUnmodifiable() throws Exception {
        // Build a Success with a mutable input list and verify the stored
        // diagnostics are an unmodifiable copy.
        java.util.ArrayList<ExecutionDiagnostic> input = new java.util.ArrayList<>();
        input.add(ExecutionDiagnostic.warning("SIR-APP-TEST-001",
                ExecutionStage.READ, "test warning"));
        ExecutionManifest manifest = new ExecutionManifest(
                temporaryDirectory.resolve("out").toAbsolutePath(),
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS,
                java.util.List.of());
        ToolchainResult.Success success = new ToolchainResult.Success(manifest, emptyGraph(), input);
        input.clear();
        assertEquals(1, success.diagnostics().size(),
                "Success must hold a defensive copy of the input diagnostics");
        assertThrows(UnsupportedOperationException.class,
                () -> success.diagnostics().clear());
    }

    // Build a minimal empty graph through the public API so this immutability test
    // does not depend on a full compilation pipeline.
    private static ProjectGraph emptyGraph() {
        ProjectGraphAnalysis analysis = new ProjectGraphBuilder().build(
                new ProjectGraphInput(
                        GraphVersion.V0_1,
                        io.kcg.sir.source.SourceId.of("manifest-test.sir"),
                        "manifest-test",
                        java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of()));
        if (analysis instanceof ProjectGraphAnalysis.Success success) {
            return success.graph();
        }
        throw new IllegalStateException("empty graph build must succeed");
    }

    @Test
    void failureBuiltFromMutableDiagnosticsIsUnmodifiable() {
        java.util.ArrayList<ExecutionDiagnostic> input = new java.util.ArrayList<>();
        input.add(ExecutionDiagnostic.error("SIR-APP-TEST-001",
                ExecutionStage.READ, "test error"));
        ToolchainResult.Failure failure = new ToolchainResult.Failure(
                ExecutionStage.READ, FailureDisposition.NO_CHANGES, input);
        input.clear();
        assertEquals(1, failure.diagnostics().size(),
                "Failure must hold a defensive copy of the input diagnostics");
        assertThrows(UnsupportedOperationException.class,
                () -> failure.diagnostics().clear());
    }

    @Test
    void successRejectsErrorDiagnostics() {
        ExecutionManifest manifest = new ExecutionManifest(
                temporaryDirectory.resolve("out").toAbsolutePath(),
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS,
                java.util.List.of());
        assertThrows(IllegalArgumentException.class, () ->
                new ToolchainResult.Success(manifest, emptyGraph(), java.util.List.of(
                        ExecutionDiagnostic.error("SIR-APP-TEST-001",
                                ExecutionStage.READ, "must not be allowed"))));
    }

    @Test
    void failureRejectsZeroErrorDiagnostics() {
        assertThrows(IllegalArgumentException.class, () ->
                new ToolchainResult.Failure(
                        ExecutionStage.READ, FailureDisposition.NO_CHANGES,
                        java.util.List.of(ExecutionDiagnostic.warning(
                                "SIR-APP-TEST-001", ExecutionStage.READ, "warning only"))));
    }

    @Test
    void failureRejectsEmptyDiagnostics() {
        assertThrows(IllegalArgumentException.class, () ->
                new ToolchainResult.Failure(
                        ExecutionStage.READ, FailureDisposition.NO_CHANGES,
                        java.util.List.of()));
    }

    @Test
    void appliedFileSymbolIdIsDefensive() {
        io.kcg.sir.lowering.api.LoweredNodeId artifact =
                new io.kcg.sir.lowering.api.LoweredNodeId("test");
        java.util.Optional<io.kcg.sir.semantic.symbol.SymbolId> symbol =
                java.util.Optional.empty();
        AppliedFile file = new AppliedFile(
                "pom.xml",
                io.kcg.sir.application.api.FileAction.CREATED,
                2L,
                "0".repeat(64),
                artifact,
                symbol);
        // Optional is immutable by contract; just verify it's present-and-empty.
        assertTrue(file.symbolId().isEmpty());
    }

    @Test
    void executionDiagnosticOptionalsAreNonNull() {
        // Constructor must reject null optionals even when other fields are valid.
        assertThrows(NullPointerException.class, () ->
                new ExecutionDiagnostic(
                        "SIR-APP-TEST-001",
                        ExecutionStage.READ,
                        ExecutionSeverity.ERROR,
                        "msg",
                        null,
                        java.util.Optional.empty(),
                                java.util.Optional.empty()));
    }

    @Test
    void manifestNormalizesItsAbsoluteOutputRoot() {
        Path unnormalized = temporaryDirectory.resolve("nested").resolve("..").resolve("out")
                .toAbsolutePath();
        ExecutionManifest manifest = new ExecutionManifest(
                unnormalized,
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS,
                java.util.List.of());

        assertEquals(unnormalized.normalize(), manifest.outputRoot());
    }

    @Test
    void appliedFileRejectsInvalidAuditMetadata() {
        io.kcg.sir.lowering.api.LoweredNodeId artifact =
                new io.kcg.sir.lowering.api.LoweredNodeId("test");
        assertThrows(IllegalArgumentException.class, () -> new AppliedFile(
                "pom.xml",
                io.kcg.sir.application.api.FileAction.CREATED,
                -1L,
                "0".repeat(64),
                artifact,
                java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AppliedFile(
                "pom.xml",
                io.kcg.sir.application.api.FileAction.CREATED,
                1L,
                "not-a-sha256",
                artifact,
                java.util.Optional.empty()));
    }

    private ToolchainResult.Success runCampusMarket() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out-" + System.nanoTime()).toAbsolutePath();
        return ApplicationTestSupport.runCampusMarket(source, outputRoot);
    }
}
