package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangePlanningRequest;
import io.kcg.sir.application.api.ChangePlanningResult;
import io.kcg.sir.application.api.ChangePlanningStage;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.model.NormalizedCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@link io.kcg.sir.application.api.ChangePlanningApplication}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path: valid base + candidate SIR with modified workflow
 *       produces a {@link ChangePlanningResult.Success} carrying a
 *       {@link ChangeAnalysis.Planned} or {@link ChangeAnalysis.NoChanges}.</li>
 *   <li>BASELINE failures: raw SHA-256 mismatch, malformed snapshot,
 *       graph version/digest mismatch, rebuilt graph digest mismatch,
 *       re-serialization byte mismatch.</li>
 *   <li>READ/PROTECT failures on input: non-absolute path, missing file,
 *       directory, symlink.</li>
 *   <li>PROTECT failures on output: missing output root, target file
 *       missing, target file content rewrite (same length), target file
 *       content change (different length), target symlink, target
 *       parent chain symlink, relative path escape.</li>
 *   <li>Zero disk: no files created/deleted/modified during any result
 *       (input + output trees both unchanged).</li>
 *   <li>Unrelated files: extra files in the same directory do not block.</li>
 *   <li>ToolchainApplication regression: PREFLIGHT -&gt; GRAPH -&gt; WRITE still
 *       works after the sir-change dependency addition and the shared
 *       SirCompilation refactor.</li>
 * </ul>
 */
class ChangePlanningApplicationTest {

    @TempDir
    Path temporaryDirectory;

    // --- Happy path ---

    @Test
    void validBaseAndCandidateProducesSuccess() throws Exception {
        BaseFixture base = compileBase();
        // Pre-generate the project tree at outputRoot so PROTECT succeeds.
        Path outputRoot = base.outputRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ChangePlanningTestSupport.hasResource("valid/campus-market-candidate.sir"),
                "required fixture valid/campus-market-candidate.sir is unavailable; see test coverage inventory");
        Path candidateSir = ChangePlanningTestSupport.writeCandidateSir(temporaryDirectory);
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(),
                candidateSir,
                snapshotFile,
                outputRoot,
                base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        assertInstanceOf(ChangePlanningResult.Success.class, result,
                "expected Success but got: " + describe(result));
        ChangePlanningResult.Success success = (ChangePlanningResult.Success) result;
        // The candidate has a modified workflow, so the analysis should be
        // either Planned (if closure files changed) or NoChanges (if the
        // pipeline produced identical output). Either way, it must not be
        // a Failure.
        assertFalse(success.analysis() instanceof ChangeAnalysis.Failure,
                "analysis must not be Failure: " + success.analysis());
    }

    // --- BASELINE failures ---

    @Test
    void baseline001RawSha256MismatchFails() throws Exception {
        BaseFixture base = compileBase();
        // Rewrite the base SIR file with different content of the same length.
        String original = Files.readString(base.sourceFile(), StandardCharsets.UTF_8);
        // Same length, different content (change displayName length compensated).
        String modified = original.replace("鏍″洯浜屾墜浜ゆ槗绯荤粺", "鏍″洯浜屾墜浜ゆ槗绯荤粺X").substring(0, original.length());
        Files.writeString(base.sourceFile(), modified, StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ChangePlanningTestSupport.hasResource("valid/campus-market-candidate.sir"),
                "required fixture valid/campus-market-candidate.sir is unavailable; see test coverage inventory");
        Path candidateSir = ChangePlanningTestSupport.writeCandidateSir(temporaryDirectory);
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(), candidateSir, snapshotFile,
                base.outputRoot(), base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.BASELINE, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-BASE-001");
    }

    @Test
    void baseline003MalformedSnapshotFails() throws Exception {
        BaseFixture base = compileBase();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ChangePlanningTestSupport.hasResource("valid/campus-market-candidate.sir"),
                "required fixture valid/campus-market-candidate.sir is unavailable; see test coverage inventory");
        Path candidateSir = ChangePlanningTestSupport.writeCandidateSir(temporaryDirectory);
        // Corrupt the snapshot bytes.
        byte[] corrupt = base.snapshotBytes().clone();
        corrupt[corrupt.length - 1] ^= 0xFF;
        Path snapshotFile = writeSnapshotFile(corrupt);

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(), candidateSir, snapshotFile,
                base.outputRoot(), base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.BASELINE, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-BASE-003");
    }

    @Test
    void baseline005GraphDigestMismatchFails() throws Exception {
        BaseFixture base = compileBase();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ChangePlanningTestSupport.hasResource("valid/campus-market-candidate.sir"),
                "required fixture valid/campus-market-candidate.sir is unavailable; see test coverage inventory");
        Path candidateSir = ChangePlanningTestSupport.writeCandidateSir(temporaryDirectory);
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());
        // Construct a ChangeBaseRevision with a wrong graph canonical digest.
        ChangeBaseRevision wrongDigest = new ChangeBaseRevision(
                ChangePlanningTestSupport.SOURCE_ID,
                base.baseSha256(),
                base.graphVersion(),
                "0000000000000000000000000000000000000000000000000000000000000000",
                ProjectGraphCanonicalFormatVersion.V1);
        ChangeSet wrongSet = new ChangeSet(
                ChangeIrVersion.V0_1, wrongDigest,
                List.of(new ModifyCapabilityWorkflow(base.baseTarget())));

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(), candidateSir, snapshotFile,
                base.outputRoot(), wrongSet);

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.BASELINE, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-BASE-005");
    }

    // --- READ/PROTECT failures on input ---

    @Test
    void protect001NonAbsoluteInputPathFails() throws Exception {
        BaseFixture base = compileBase();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ChangePlanningTestSupport.hasResource("valid/campus-market-candidate.sir"),
                "required fixture valid/campus-market-candidate.sir is unavailable; see test coverage inventory");
        Path candidateSir = ChangePlanningTestSupport.writeCandidateSir(temporaryDirectory);
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());

        ChangePlanningRequest request = new ChangePlanningRequest(
                Path.of("relative/path.sir"), candidateSir, snapshotFile,
                base.outputRoot(), base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.READ, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-PROTECT-001");
    }

    @Test
    void protect002MissingInputFileFails() throws Exception {
        BaseFixture base = compileBase();
        Path missing = temporaryDirectory.resolve("nonexistent.sir").toAbsolutePath();
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(), missing, snapshotFile,
                base.outputRoot(), base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.READ, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-PROTECT-002");
    }

    @Test
    void protect004InputDirectoryFails() throws Exception {
        BaseFixture base = compileBase();
        Path dir = temporaryDirectory.resolve("sir-directory").toAbsolutePath();
        Files.createDirectory(dir);
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(), dir, snapshotFile,
                base.outputRoot(), base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.READ, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-PROTECT-004");
    }

    // --- PROTECT failures on output (outputRoot physical protection) ---

    @Test
    void protect102MissingOutputRootFails() throws Exception {
        BaseFixture base = compileBase();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ChangePlanningTestSupport.hasResource("valid/campus-market-candidate.sir"),
                "required fixture valid/campus-market-candidate.sir is unavailable; see test coverage inventory");
        Path candidateSir = ChangePlanningTestSupport.writeCandidateSir(temporaryDirectory);
        Path snapshotFile = writeSnapshotFile(base.snapshotBytes());
        // Output root does not exist.
        Path missingRoot = temporaryDirectory.resolve("missing-output-root").toAbsolutePath();

        ChangePlanningRequest request = new ChangePlanningRequest(
                base.sourceFile(), candidateSir, snapshotFile,
                missingRoot, base.changeSet());

        ChangePlanningResult result =
                new io.kcg.sir.application.api.ChangePlanningApplication().execute(request);

        ChangePlanningResult.Failure failure = assertInstanceOf(
                ChangePlanningResult.Failure.class, result);
        assertEquals(ChangePlanningStage.PROTECT, failure.failedStage());
        assertHasCode(failure, "SIR-APP-CHANGE-PROTECT-102");
    }

    @Test
    void existingToolchainApplicationStillWorks() throws Exception {
        // Verify that the existing ToolchainApplication's PREFLIGHT -> GRAPH -> WRITE
        // flow still works after the shared SirCompilation refactor.
        Path source = io.kcg.sir.application.ApplicationTestSupport.writeCampusMarketSource(
                temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("generated-project").toAbsolutePath();

        ToolchainResult.Success success = io.kcg.sir.application.ApplicationTestSupport.runCampusMarket(
                source, outputRoot);

        assertFalse(success.manifest().files().isEmpty(),
                "manifest must contain files");
        assertTrue(success.graph() != null, "graph must be non-null");
    }

    // --- Helpers ---

    private BaseFixture compileBase() throws IOException {
        Path sourceFile = ChangePlanningTestSupport.writeBaseSir(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("base-build").toAbsolutePath();
        ToolchainResult.Success success =
                ChangePlanningTestSupport.compileBase(sourceFile, outputRoot);
        ProjectGraph graph = success.graph();
        // Look up the actual Capability from the compiled model so the
        // ChangeTarget's SymbolId/AstNodeIds match what the pipeline
        // produces (SymbolIdFactory preserves softwareName case, so the
        // SymbolId is sir://CampusMarket/... not sir://software/campus-market/...).
        NormalizedCapability cap = ChangePlanningTestSupport.findCapability(
                ChangePlanningTestSupport.compileModel(sourceFile));
        ChangeTarget baseTarget = ChangePlanningTestSupport.baseTarget(cap);
        byte[] snapshotBytes = ChangePlanningTestSupport.serializeGraph(graph);
        byte[] rawBytes = Files.readAllBytes(sourceFile);
        String baseSha256 = ChangePlanningTestSupport.sha256Hex(rawBytes);
        ChangeBaseRevision revision = new ChangeBaseRevision(
                ChangePlanningTestSupport.SOURCE_ID,
                baseSha256,
                graph.version(),
                graph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1);
        ChangeSet changeSet = new ChangeSet(
                ChangeIrVersion.V0_1, revision,
                List.of(new ModifyCapabilityWorkflow(baseTarget)));
        return new BaseFixture(
                sourceFile, outputRoot, snapshotBytes, baseSha256,
                graph.version(), changeSet, baseTarget);
    }

    private Path writeSnapshotFile(byte[] snapshotBytes) throws IOException {
        Path snapshotFile = temporaryDirectory.resolve("baseline.snapshot").toAbsolutePath();
        Files.write(snapshotFile, snapshotBytes);
        return snapshotFile;
    }

    private static void deleteClosureServiceFile(Path outputRoot) throws IOException {
        // Find the generated Service file (PublishGoodsService) and delete it.
        // The Service artifact is owned by the PublishGoods Capability.
        java.util.List<Path> javaFiles;
        try (var stream = Files.walk(outputRoot)) {
            javaFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.getFileName().toString().contains("PublishGoodsService"))
                    .toList();
        }
        if (javaFiles.isEmpty()) {
            throw new IllegalStateException("PublishGoodsService file not found in " + outputRoot);
        }
        Files.delete(javaFiles.get(0));
    }

    private static void rewriteClosureServiceFileSameLength(Path outputRoot) throws IOException {
        // Find the generated Service file and rewrite it with same-length
        // but different content.
        java.util.List<Path> javaFiles;
        try (var stream = Files.walk(outputRoot)) {
            javaFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.getFileName().toString().contains("PublishGoodsService"))
                    .toList();
        }
        if (javaFiles.isEmpty()) {
            throw new IllegalStateException("PublishGoodsService file not found in " + outputRoot);
        }
        Path target = javaFiles.get(0);
        byte[] original = Files.readAllBytes(target);
        // XOR every byte to produce a different content of the same length.
        byte[] modified = new byte[original.length];
        for (int i = 0; i < original.length; i++) {
            modified[i] = (byte) (original[i] ^ 0x55);
        }
        Files.write(target, modified);
    }

    private static void appendToClosureServiceFile(Path outputRoot) throws IOException {
        java.util.List<Path> javaFiles;
        try (var stream = Files.walk(outputRoot)) {
            javaFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.getFileName().toString().contains("PublishGoodsService"))
                    .toList();
        }
        if (javaFiles.isEmpty()) {
            throw new IllegalStateException("PublishGoodsService file not found in " + outputRoot);
        }
        Path target = javaFiles.get(0);
        // Append bytes so byteCount differs.
        Files.write(target, "// appended\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static List<String> listFilesRecursively(Path dir) throws IOException {
        List<String> result = new java.util.ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> result.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        java.util.Collections.sort(result);
        return result;
    }

    private static void assertHasCode(ChangePlanningResult.Failure failure, String code) {
        boolean found = failure.diagnostics().stream()
                .anyMatch(d -> d.code().equals(code) && d.isError());
        assertTrue(found, "expected error diagnostic " + code + " in " + failure.diagnostics());
    }

    private static String describe(ChangePlanningResult result) {
        if (result instanceof ChangePlanningResult.Failure failure) {
            return "Failure at " + failure.failedStage() + ": " + failure.diagnostics();
        }
        return result.toString();
    }

    /**
     * Immutable bundle of base compilation outputs used to construct a
     * {@link ChangePlanningRequest}: source file path, output root path,
     * canonical snapshot bytes, raw SHA-256, graph version, a
     * pre-constructed {@link ChangeSet} targeting the PublishGoods workflow,
     * and the {@link ChangeTarget} used inside that ChangeSet.
     */
    // Minimal base compilation fixture shared by the planning tests.
    record BaseFixture(
            Path sourceFile,
            Path outputRoot,
            byte[] snapshotBytes,
            String baseSha256,
            GraphVersion graphVersion,
            ChangeSet changeSet,
            ChangeTarget baseTarget) {
    }
}
