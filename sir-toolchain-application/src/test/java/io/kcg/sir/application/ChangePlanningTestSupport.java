package io.kcg.sir.application;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.internal.SirCompiler;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared test support for change-planning application tests. Compiles the
 * base campus-market SIR through the full toolchain pipeline to produce a
 * baseline {@link ProjectGraph} + snapshot bytes, and provides helpers to
 * construct {@link ChangePlanningRequest}s.
 */
final class ChangePlanningTestSupport {

    static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");

    private ChangePlanningTestSupport() {
    }

    /**
     * Write the base campus-market SIR source to {@code dir} and return
     * the absolute path.
     */
    static Path writeBaseSir(Path dir) throws IOException {
        return writeSir(dir, "campus-market.sir", "valid/campus-market.sir");
    }

    /**
     * Write the candidate campus-market SIR source (modified workflow:
     * validate condition changed from {@code >= 0.01} to {@code > 0.0}) to
     * {@code dir} and return the absolute path.
     */
    static Path writeCandidateSir(Path dir) throws IOException {
        return writeSir(dir, "campus-market-candidate.sir",
                "valid/campus-market-candidate.sir");
    }

    /**
     * Write a custom SIR source string to {@code dir} under the given
     * filename and return the absolute path.
     */
    static Path writeSir(Path dir, String filename, String resourcePath) throws IOException {
        Path source = dir.resolve(filename);
        Files.writeString(source, resource(resourcePath), StandardCharsets.UTF_8);
        return source.toAbsolutePath();
    }

    /**
     * Write raw bytes to {@code dir} under the given filename and return
     * the absolute path. Used for same-length rewrite tests.
     */
    static Path writeRawBytes(Path dir, String filename, byte[] bytes) throws IOException {
        Path source = dir.resolve(filename);
        Files.write(source, bytes);
        return source.toAbsolutePath();
    }

    /**
     * Compile the base campus-market SIR through the full toolchain
     * pipeline and return the {@link ToolchainResult.Success} carrying
     * the validated {@link ProjectGraph}.
     */
    static ToolchainResult.Success compileBase(Path sourceFile, Path outputRoot)
            throws IOException {
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                sourceFile.toAbsolutePath(),
                SOURCE_ID,
                outputRoot.toAbsolutePath(),
                io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS));
        if (!(result instanceof ToolchainResult.Success success)) {
            ToolchainResult.Failure failure = (ToolchainResult.Failure) result;
            throw new AssertionError("expected Success but got Failure at "
                    + failure.failedStage() + ": " + failure.diagnostics());
        }
        return success;
    }

    /**
     * Serialize a {@link ProjectGraph} to canonical snapshot bytes (V1).
     */
    static byte[] serializeGraph(ProjectGraph graph) {
        ProjectGraphSerialization serialization =
                new ProjectGraphSerializer().serialize(
                        graph, ProjectGraphCanonicalFormatVersion.V1);
        if (serialization instanceof ProjectGraphSerialization.Failure failure) {
            throw new AssertionError("serialization failed: " + failure.diagnostics());
        }
        return ((ProjectGraphSerialization.Success) serialization).document().bytes();
    }

    /**
     * Compute the lowercase hex SHA-256 of raw bytes.
     */
    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Read a test resource file as a UTF-8 string.
     */
    static String resource(String path) throws IOException {
        try (InputStream stream =
                     ChangePlanningTestSupport.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** True when an optional classpath fixture exists. */
    static boolean hasResource(String path) {
        return ChangePlanningTestSupport.class.getResourceAsStream("/" + path) != null;
    }

    /**
     * Read a test resource file as raw bytes.
     */
    static byte[] resourceBytes(String path) throws IOException {
        try (InputStream stream =
                     ChangePlanningTestSupport.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return stream.readAllBytes();
        }
    }

    /**
     * Compile a SIR source file via {@link SirCompiler} (pure in-memory)
     * and return the resulting {@link NormalizedSemanticModel}. Used to
     * look up the actual SymbolId/AstNodeIds produced by the pipeline,
     * which preserve the softwareName's original case (e.g.
     * {@code CampusMarket}, not {@code campus-market}).
     */
    static NormalizedSemanticModel compileModel(Path sourceFile) throws IOException {
        String sourceText = Files.readString(sourceFile, StandardCharsets.UTF_8);
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        Optional<SirCompiler.CompiledProject> compiled =
                SirCompiler.compile(sourceText, SOURCE_ID, diagnostics);
        if (compiled.isEmpty()) {
            throw new AssertionError("SirCompiler.compile failed: " + diagnostics);
        }
        return compiled.get().semanticModel();
    }

    /**
     * Find the PublishGoods Capability in a compiled semantic model and
     * return it. Throws if not found.
     */
    static NormalizedCapability findCapability(NormalizedSemanticModel model) {
        for (NormalizedDeclaration decl : model.declarations()) {
            if (decl instanceof NormalizedCapability cap
                    && cap.name().equals("PublishGoods")) {
                return cap;
            }
        }
        throw new AssertionError("PublishGoods capability not found in model");
    }

    /**
     * Construct the baseline {@link ChangeTarget} for the given capability's
     * workflow. The target's SymbolId/AstNodeIds are taken directly from
     * the compiled model so they match exactly what the pipeline produces.
     */
    static ChangeTarget baseTarget(NormalizedCapability cap) {
        return new ChangeTarget(
                cap.id(),
                cap.sourceNodeId(),
                cap.workflow().sourceNodeId());
    }

    /**
     * Immutable snapshot of the base SIR compilation: source file path,
     * canonical snapshot bytes, raw SHA-256, graph version, a
     * pre-constructed {@link ChangeSet} targeting the PublishGoods workflow,
     * and the {@link ChangeTarget} used inside that ChangeSet (exposed so
     * tests that build custom ChangeSets can reuse the same target without
     * recompiling).
     */
    record BaseSnapshot(
            Path sourceFile,
            byte[] snapshotBytes,
            String baseSha256,
            GraphVersion graphVersion,
            ChangeSet changeSet,
            ChangeTarget baseTarget
    ) {
        BaseSnapshot {
            java.util.Objects.requireNonNull(sourceFile, "sourceFile");
            snapshotBytes = snapshotBytes.clone();
            java.util.Objects.requireNonNull(baseSha256, "baseSha256");
            java.util.Objects.requireNonNull(graphVersion, "graphVersion");
            java.util.Objects.requireNonNull(changeSet, "changeSet");
            java.util.Objects.requireNonNull(baseTarget, "baseTarget");
        }

        @Override
        public byte[] snapshotBytes() {
            return snapshotBytes.clone();
        }
    }
}
