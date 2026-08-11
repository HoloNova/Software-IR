package io.kcg.cli;

import io.kcg.sir.application.api.ChangeApplyRequest;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselinePlanningRequest;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.application.api.ChangeBaselineRegistrationRequest;
import io.kcg.sir.application.api.ChangeBaselineRegistrationResult;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.source.SourceId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Self-contained test fixture helper for kcg-cli tests. Uses only public
 * Application API to compile a base SIR, build/serialize the Project Graph,
 * and register the B0 Baseline Bundle — mirroring {@code ChangeExecutionTestSupport}
 * without access to its package-private test-scoped helpers.
 */
public final class CliTestFixtures {

    static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");

    private CliTestFixtures() {
    }

    public record RegisteredBase(Path sourceFile, Path outputRoot, byte[] snapshotBytes,
                          String baseSha256, ChangeBaseRevision revision,
                          String baselineId, Path stateRoot) {
    }

    /** Result of a successful Apply that publishes B1. */
    public record AppliedB1(String b0BaselineId, String b1BaselineId, Path stateRoot, Path outputRoot) {}

    /**
     * Register B0, plan, and apply to publish a legitimate B1 — all via
     * public Application API. Returns the B0 and new B1 identity.
     */
    static AppliedB1 applyB1(RegisteredBase b0, Path candidateSir, ChangeOperation op,
                              ChangeIrVersion version) throws IOException {
        ChangeSet cs = new ChangeSet(version, b0.revision, java.util.List.of(op));
        ChangeExecutionApplication app = new ChangeExecutionApplication();
        ChangeBaselinePlanningRequest planReq = new ChangeBaselinePlanningRequest(
                b0.stateRoot, b0.baselineId, candidateSir, b0.outputRoot, cs);
        ChangeBaselinePlanningResult planRes = app.plan(planReq);
        if (!(planRes instanceof ChangeBaselinePlanningResult.Success ps)) {
            throw new AssertionError("plan failed: " + planRes.diagnostics());
        }
        ChangeApplyRequest applyReq = new ChangeApplyRequest(
                b0.stateRoot, b0.baselineId, candidateSir, b0.outputRoot, cs);
        ChangeApplyResult applyRes = app.apply(applyReq);
        if (!(applyRes instanceof ChangeApplyResult.Applied as)) {
            throw new AssertionError("apply failed: " + applyRes.diagnostics());
        }
        return new AppliedB1(b0.baselineId, as.newBaselineReceipt().baselineId(), b0.stateRoot, b0.outputRoot);
    }

    static String resource(String name) throws IOException {
        try (var s = CliTestFixtures.class.getResourceAsStream("/io/kcg/cli/" + name)) {
            if (s == null) {
                throw new IllegalArgumentException("missing resource: " + name);
            }
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** True when a classpath test resource exists (used for explicit skip reasons). */
    static boolean resourceExists(String name) {
        try (var s = CliTestFixtures.class.getResourceAsStream("/io/kcg/cli/" + name)) {
            return s != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Path writeSir(Path dir, String name, String resourceName) throws IOException {
        Path f = dir.resolve(name).toAbsolutePath();
        Files.writeString(f, resource(resourceName), StandardCharsets.UTF_8);
        return f;
    }

    static RegisteredBase registerBase(Path tempDir, String sirResource, String buildName)
            throws IOException {
        Path sourceFile = writeSir(tempDir, "base.sir", sirResource);
        Path outputRoot = tempDir.resolve(buildName).toAbsolutePath();
        ToolchainResult.Success success = compile(sourceFile, outputRoot);
        ProjectGraph graph = success.graph();
        byte[] snapshotBytes;
        ProjectGraphSerialization serialization = new ProjectGraphSerializer().serialize(
                graph, ProjectGraphCanonicalFormatVersion.V1);
        if (serialization instanceof ProjectGraphSerialization.Failure f) {
            throw new AssertionError("serialization failed: " + f.diagnostics());
        }
        snapshotBytes = ((ProjectGraphSerialization.Success) serialization).document().bytes();
        byte[] rawBytes = Files.readAllBytes(sourceFile);
        String baseSha256 = sha256Hex(rawBytes);
        ChangeBaseRevision revision = new ChangeBaseRevision(
                SOURCE_ID, baseSha256, graph.version(),
                graph.canonicalDigest(), ProjectGraphCanonicalFormatVersion.V1);

        Path stateRoot = tempDir.resolve("state-root").toAbsolutePath();
        Files.createDirectories(stateRoot);
        Path snapshotFile = tempDir.resolve("baseline.kcg-psg").toAbsolutePath();
        Files.write(snapshotFile, snapshotBytes);

        ChangeExecutionApplication app = new ChangeExecutionApplication();
        ChangeBaselineRegistrationRequest regReq = new ChangeBaselineRegistrationRequest(
                sourceFile, snapshotFile, outputRoot, stateRoot, revision);
        ChangeBaselineRegistrationResult reg = app.register(regReq);
        if (!(reg instanceof ChangeBaselineRegistrationResult.Success s)) {
            throw new AssertionError("registration failed: " + reg);
        }
        return new RegisteredBase(sourceFile, outputRoot, snapshotBytes, baseSha256,
                revision, s.receipt().baselineId(), stateRoot);
    }

    static ToolchainResult.Success compile(Path sourceFile, Path outputRoot) {
        ToolchainRequest req = new ToolchainRequest(sourceFile.toAbsolutePath(), SOURCE_ID,
                outputRoot.toAbsolutePath(), ConflictPolicy.FAIL_IF_EXISTS);
        ToolchainResult result = new ToolchainApplication().execute(req);
        if (!(result instanceof ToolchainResult.Success s)) {
            throw new AssertionError("compile failed: " + result);
        }
        return s;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}


