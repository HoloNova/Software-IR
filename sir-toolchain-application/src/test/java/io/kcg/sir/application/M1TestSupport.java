package io.kcg.sir.application.api;

import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Shared public-API-only test support for ADR-019 MVP slice M1 tests
 * (generated-baseline registration and digest-bound Apply). Intentionally
 * mirrors the Stage F pattern: all setup goes through public Application
 * APIs; the package-private {@code ChangeExecutionApplication} seams and
 * constructors are accessed directly because these tests live in
 * {@code io.kcg.sir.application.api}.
 */
final class M1TestSupport {

    static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");

    private M1TestSupport() {
    }

    /**
     * Compiled base fixture: source file, generated outputRoot, canonical
     * snapshot bytes and the exact base revision — all derived from one
     * {@link ToolchainApplication} run.
     */    record Fixture(Path sourceFile, Path outputRoot, byte[] snapshotBytes,
                    ChangeBaseRevision revision) {
        Fixture {
            Objects.requireNonNull(sourceFile, "sourceFile");
            Objects.requireNonNull(outputRoot, "outputRoot");
            snapshotBytes = snapshotBytes.clone();
            Objects.requireNonNull(revision, "revision");
        }

        @Override
        public byte[] snapshotBytes() {
            return snapshotBytes.clone();
        }
    }

    /** Compile a SIR resource into a {@link Fixture} under tempDir. */
    static Fixture prepareFixture(Path tempDir, String sourceFilename, String resourcePath)
            throws IOException {
        Path sourceFile = writeSir(tempDir, sourceFilename, resourcePath);
        Path outputRoot = tempDir.resolve(sourceFilename + "-build").toAbsolutePath();
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                sourceFile.toAbsolutePath(), SOURCE_ID,
                outputRoot.toAbsolutePath(), ConflictPolicy.FAIL_IF_EXISTS));
        ToolchainResult.Success success =
                assertInstanceOf(ToolchainResult.Success.class, result,
                        () -> "expected generation Success: " + result);
        ProjectGraph graph = success.graph();
        byte[] snapshotBytes = serializeGraph(graph);
        String baseSha = sha256Hex(Files.readAllBytes(sourceFile));
        ChangeBaseRevision revision = new ChangeBaseRevision(
                SOURCE_ID, baseSha, graph.version(), graph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1);
        return new Fixture(sourceFile, outputRoot, snapshotBytes, revision);
    }

    static Path writeSir(Path tempDir, String filename, String resourcePath)
            throws IOException {
        Path source = tempDir.resolve(filename).toAbsolutePath();
        Files.writeString(source, resource(resourcePath), StandardCharsets.UTF_8);
        return source;
    }

    static String resource(String path) throws IOException {
        try (var stream = M1TestSupport.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static byte[] serializeGraph(ProjectGraph graph) {
        ProjectGraphSerialization serialization = new ProjectGraphSerializer().serialize(
                graph, ProjectGraphCanonicalFormatVersion.V1);
        return assertInstanceOf(ProjectGraphSerialization.Success.class, serialization,
                () -> "serialization failed: " + serialization).document().bytes();
    }

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

    static Path writeSnapshotFile(Path tempDir, byte[] snapshotBytes) throws IOException {
        Path snapshotFile = tempDir.resolve("baseline.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, snapshotBytes);
        return snapshotFile;
    }

    /** Snapshot-backed registration (legacy API, unchanged semantics). */
    static ChangeBaselineRegistrationResult.Success registerBaseline(
            ChangeExecutionApplication app, Fixture fixture, Path stateRoot) throws IOException {
        Path snapshotFile = writeSnapshotFile(stateRoot.getParent(), fixture.snapshotBytes());
        ChangeBaselineRegistrationRequest request = new ChangeBaselineRegistrationRequest(
                fixture.sourceFile(), snapshotFile, fixture.outputRoot(), stateRoot,
                fixture.revision());
        ChangeBaselineRegistrationResult result = app.register(request);
        return assertInstanceOf(ChangeBaselineRegistrationResult.Success.class, result,
                () -> "registration failed: " + result);
    }

    /** Generated-baseline registration (M1-A entry). */
    static ChangeBaselineRegistrationResult registerGeneratedBaseline(
            ChangeExecutionApplication app, Fixture fixture, Path stateRoot) {
        GeneratedBaselineRegistrationRequest request = new GeneratedBaselineRegistrationRequest(
                fixture.sourceFile(), SOURCE_ID, fixture.outputRoot(), stateRoot);
        return app.registerGeneratedBaseline(request);
    }

    /** Fresh context inspection (public API); asserts Success. */
    static ChangePlanningContext context(
            ChangeExecutionApplication app, Path stateRoot, Path outputRoot, Path candidate) {
        ChangePlanningContextRequest request =
                new ChangePlanningContextRequest(stateRoot, outputRoot, candidate);
        ChangePlanningContextResult result = app.inspectChangePlanningContext(request);
        return assertInstanceOf(ChangePlanningContextResult.Success.class, result,
                () -> "context inspection failed: " + result.diagnostics()).context();
    }

    /** Find one catalog target by side/kind/declaration name. */
    static ChangePlanningTarget findTarget(ChangePlanningContext ctx,
            ChangePlanningSide side, ChangePlanningTargetKind kind, String declarationName) {
        return findTarget(ctx, side, kind, declarationName, null);
    }

    /** Find one catalog target by side/kind/declaration name and optional target name. */
    static ChangePlanningTarget findTarget(ChangePlanningContext ctx,
            ChangePlanningSide side, ChangePlanningTargetKind kind, String declarationName,
            String targetName) {
        List<ChangePlanningTarget> matches = ctx.targets().stream()
                .filter(t -> t.side() == side && t.kind() == kind
                        && t.declarationDisplayName().equals(declarationName)
                        && (targetName == null
                                || t.targetDisplayName().orElse("").equals(targetName)))
                .toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one target for " + side + "/" + kind
                    + "/" + declarationName + "/" + targetName + ", got " + matches.size());
        }
        return matches.get(0);
    }

    /** v0.4 ModifyInputFieldConstraints ChangeSet from a context-derived base target. */
    static ChangeSet v04ChangeSet(ChangePlanningContext ctx) {
        ChangePlanningTarget target = findTarget(ctx, ChangePlanningSide.BASE,
                ChangePlanningTargetKind.INPUT_FIELD, "PublishGoodsInput", "title");
        return new ChangeSet(ChangeIrVersion.V0_4, ctx.baseline().revision(),
                List.of(new ModifyInputFieldConstraints(target.target())));
    }

    /** v0.2 AddCapability ChangeSet from a context-derived candidate target. */
    static ChangeSet addCapabilityChangeSet(ChangePlanningContext ctx) {
        ChangePlanningTarget target = findTarget(ctx, ChangePlanningSide.CANDIDATE,
                ChangePlanningTargetKind.CAPABILITY_WORKFLOW, "SearchGoods");
        return new ChangeSet(ChangeIrVersion.V0_2, ctx.baseline().revision(),
                List.of(new AddCapability(target.target())));
    }

    /** v0.3 RemoveCapability ChangeSet from a context-derived base target. */
    static ChangeSet removeCapabilityChangeSet(ChangePlanningContext ctx) {
        ChangePlanningTarget target = findTarget(ctx, ChangePlanningSide.BASE,
                ChangePlanningTargetKind.CAPABILITY_WORKFLOW, "PublishGoods");
        return new ChangeSet(ChangeIrVersion.V0_3, ctx.baseline().revision(),
                List.of(new RemoveCapability(target.target())));
    }

    /** Any ChangeSet whose {@code basedOn()} matches the context receipt. */
    static ChangeTarget anyInputFieldTarget(ChangePlanningContext ctx) {
        return findTarget(ctx, ChangePlanningSide.BASE,
                ChangePlanningTargetKind.INPUT_FIELD, "PublishGoodsInput", "title").target();
    }

    static String readCurrent(Path stateRoot) throws IOException {
        return Files.readString(stateRoot.resolve("CURRENT"), StandardCharsets.US_ASCII).trim();
    }
}
