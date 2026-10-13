package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.CandidateVerificationApplication;
import io.kcg.sir.application.api.CleanupProof;
import io.kcg.sir.application.api.CompilationInput;
import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.SirValidationApplication;
import io.kcg.sir.application.api.SirValidationRequest;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.StageResult;
import io.kcg.sir.application.api.StageStatus;
import io.kcg.sir.application.api.VerificationPorts;
import io.kcg.sir.application.api.VerificationPorts.BuildOutcome;
import io.kcg.sir.application.api.VerificationPorts.Sandbox;
import io.kcg.sir.application.api.VerificationPorts.StartOutcome;
import io.kcg.sir.application.api.VerificationReport;
import io.kcg.sir.application.api.VerificationRequest;
import io.kcg.sir.application.api.VerificationStage;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The orchestration is tested with fake ports, so what is under test is the contract: which level proves
 * what, what is NOT_RUN and why, that the build sees exactly the generated files, and that everything opened
 * is closed in order. The expected files and digests come from the production toolchain and the stored
 * baseline, never from the verification itself.
 */
class CandidateVerificationTest {
    @TempDir Path temp;

    // ------------------------------------------------------------------ fakes

    private final List<String> events = new ArrayList<>();
    private int directories;

    private final class FakeSandbox implements Sandbox {
        Sandbox.Unavailable noDatabase;
        Sandbox.Unavailable nothing;
        boolean prefill;
        CleanupProof release = CleanupProof.proved("directory removed");

        @Override public Lease open(boolean needsDatabase) throws Unavailable {
            events.add("open(" + needsDatabase + ")");
            if (nothing != null) throw nothing;
            if (needsDatabase && noDatabase != null) throw noDatabase;
            int number = ++directories;
            Path root = temp.resolve("candidate-" + number);
            Path evidence = temp.resolve("evidence-" + number);
            try {
                Files.createDirectories(root);
                Files.createDirectories(evidence);
                if (prefill) Files.writeString(root.resolve("stray.txt"), "left over");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            return new Lease() {
                @Override public Path candidateRoot() { return root; }
                @Override public Path evidenceRoot() { return evidence; }
                @Override public Map<String, String> applicationEnvironment() {
                    return needsDatabase ? Map.of("SPRING_DATASOURCE_URL", "jdbc:fake") : Map.of();
                }
                @Override public CleanupProof release() { events.add("release"); return release; }
            };
        }

        @Override public Map<String, String> environment() { return Map.of("database", "fake-1.0"); }
    }

    private final class FakeBuilder implements VerificationPorts.Builder {
        Map<String, String> seen;
        StageOutcome result = StageOutcome.passed("built");
        boolean writeArtifact = true;
        Path artifactOverride;
        boolean throwing;

        @Override public BuildOutcome build(Path projectRoot, Path evidenceRoot, ResourceLimits limits) {
            events.add("build");
            if (throwing) throw new IllegalStateException("password=s3cret");
            try {
                seen = MultiSourceTestSupport.files(projectRoot);
                Path artifact = projectRoot.resolve("target/app.jar");
                if (writeArtifact) {
                    Files.createDirectories(artifact.getParent());
                    Files.writeString(artifact, "jar");
                }

                return new BuildOutcome(result, result.status() == StageStatus.PASSED
                        ? Optional.of(artifactOverride != null ? artifactOverride : artifact) : Optional.empty());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public Map<String, String> environment() { return Map.of("maven", "fake"); }
    }

    private final class FakeHost implements VerificationPorts.Host {
        StageOutcome result = StageOutcome.passed("ready");
        CleanupProof stopProof = CleanupProof.proved("process exited");
        boolean stopThrows;
        List<io.kcg.sir.application.api.LogRef> stopLogs = List.of();
        Map<String, String> seenEnvironment;

        @Override public StartOutcome start(Path artifact, Map<String, String> environment, Path evidenceRoot, ResourceLimits limits) {
            events.add("start");
            seenEnvironment = environment;
            VerificationPorts.Running running = new VerificationPorts.Running() {
                @Override public URI baseUrl() { return URI.create("http://127.0.0.1:1"); }
                @Override public VerificationPorts.Stopped stop() {
                    events.add("stop");
                    if (stopThrows) throw new IllegalStateException("boom");
                    return new VerificationPorts.Stopped(stopProof, stopLogs);
                }
            };
            return new StartOutcome(result, Optional.of(running));
        }

        @Override public Map<String, String> environment() { return Map.of("java", "fake"); }
    }

    private final class FakeOracle implements VerificationPorts.Oracle {
        StageOutcome result = StageOutcome.passed("requirement holds");
        boolean throwing;

        @Override public String revision() { return "FAKE_REQUIREMENT_V1"; }
        @Override public StageOutcome check(URI baseUrl, Path evidenceRoot, ResourceLimits limits) {
            events.add("check");
            if (throwing) throw new IllegalStateException("password=s3cret");
            return result;
        }
    }

    private record Fixture(CompilationInput input, Map<String, String> generatedTree, String sourceSetSha, String manifestDigest) {}

    /** The multi-file fixture, its from-scratch generation, and the identity a stored baseline gives it. */
    private Fixture project() throws Exception {
        Path source = MultiSourceTestSupport.fixture(temp.resolve("src"));
        Path output = temp.resolve("oracle-output");
        ProjectToolchainResult.Success generated = MultiSourceTestSupport.generate(source, output);
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class, new ProjectBaselineApplication()
                .register(new ProjectBaselineRegistrationRequest(generated.sources(), output, temp.resolve("oracle-state"))));
        return new Fixture(CompilationInput.project(generated.sources()), MultiSourceTestSupport.files(output),
                saved.receipt().sourceSetSha256Hex(), saved.receipt().manifestDigest());
    }

    private CandidateVerificationApplication full(FakeSandbox sandbox, FakeBuilder builder, FakeHost host, FakeOracle oracle) {
        return new CandidateVerificationApplication(sandbox, builder, host, oracle, ResourceLimits.defaults());
    }

    private static VerificationRequest request(Fixture f, VerificationStage through) {
        return new VerificationRequest("candidate-1", f.input(), through);
    }

    private static void assertStages(VerificationReport report, StageStatus... expected) {
        List<StageStatus> actual = report.stages().stream().map(StageResult::status).toList();
        assertEquals(List.of(expected), actual, () -> report.canonicalText());
    }

    // ------------------------------------------------------------------ static

    @Test void staticOnlyBindsTheCandidateToItsStoredIdentityAndOpensNothing() throws Exception {
        Fixture f = project();
        var sandbox = new FakeSandbox();
        var report = full(sandbox, new FakeBuilder(), new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.STATIC));

        assertStages(report, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals("NOT_REQUESTED", report.stage(VerificationStage.BUILD).outcome().code());
        assertEquals("NOT_REQUESTED", report.stage(VerificationStage.BUSINESS).outcome().code());
        var binding = report.binding().orElseThrow();
        assertEquals(f.sourceSetSha(), binding.sourceSetSha256(), "same source identity a stored baseline gets");
        assertEquals(f.manifestDigest(), binding.manifestDigest(), "same file-list identity a stored baseline gets");
        assertEquals(f.generatedTree().size(), binding.fileCount());
        assertEquals(CompilationInput.Kind.PROJECT, binding.inputKind());
        assertEquals(List.of(), events, "static generation must not touch the sandbox");
        assertEquals(CleanupProof.nothingToClean(), report.cleanup());
        assertTrue(report.environment().isEmpty());
    }

    @Test void aSingleFileIsBoundToTheSameDigestTheStaticCheckerGives() throws Exception {
        String sir = ApplicationTestSupport.resource("valid/campus-market.sir");
        var checked = new SirValidationApplication().check(new SirValidationRequest("campus", sir));
        assertTrue(checked.ok());
        var report = CandidateVerificationApplication.staticOnly().verify(new VerificationRequest("campus",
                CompilationInput.singleFile(SourceId.of("campus-market.sir"), sir), VerificationStage.STATIC));

        assertTrue(report.passedThrough(VerificationStage.STATIC));
        assertEquals(checked.digest(), report.binding().orElseThrow().generatedDigest());
        assertEquals(checked.fileCount(), report.binding().orElseThrow().fileCount());
        assertEquals(CompilationInput.Kind.SINGLE_FILE, report.binding().orElseThrow().inputKind());
    }

    @Test void aStaticFailureKeepsTheOriginalDiagnosticsAndNothingAfterItRuns() throws Exception {
        String sir = ApplicationTestSupport.resource("invalid/semantic-unresolved-name.sir");
        var sandbox = new FakeSandbox();
        var report = full(sandbox, new FakeBuilder(), new FakeHost(), new FakeOracle()).verify(new VerificationRequest("bad",
                CompilationInput.singleFile(SourceId.of("bad.sir"), sir), VerificationStage.BUSINESS));

        assertStages(report, StageStatus.FAILED, StageStatus.NOT_RUN, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        StageOutcome failed = report.stage(VerificationStage.STATIC).outcome();
        assertEquals("STATIC_SEMANTIC", failed.code());
        assertFalse(failed.diagnostics().isEmpty());
        assertTrue(failed.diagnostics().get(0).startsWith("[SEMANTIC] "), failed.diagnostics().get(0));
        assertEquals("PREVIOUS_STAGE_NOT_PASSED", report.stage(VerificationStage.BUILD).outcome().code());
        assertTrue(report.binding().isEmpty(), "no project was generated, so there is nothing to bind to");
        assertEquals(List.of(), events);
    }

    // ------------------------------------------------------------------ build / start / business

    @Test void theBuildSeesExactlyTheFilesAFromScratchGenerationProduces() throws Exception {
        Fixture f = project();
        var builder = new FakeBuilder();
        var report = full(new FakeSandbox(), builder, new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.BUILD));

        assertStages(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals(f.generatedTree(), builder.seen, "byte-for-byte the toolchain's own generation, and nothing else");
        assertEquals("NOT_REQUESTED", report.stage(VerificationStage.START).outcome().code());
        assertEquals(List.of("open(false)", "build", "release"), events, "no database is opened for a build-only request");
        assertEquals(Map.of("builder.maven", "fake", "sandbox.database", "fake-1.0"), report.environment());
    }

    @Test void aFullPassRunsInOrderAndStopsTheApplicationBeforeReleasingTheSandbox() throws Exception {
        Fixture f = project();
        var host = new FakeHost();
        var report = full(new FakeSandbox(), new FakeBuilder(), host, new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));

        assertStages(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED);
        assertEquals(List.of("open(true)", "build", "start", "check", "stop", "release"), events);
        assertEquals(Map.of("SPRING_DATASOURCE_URL", "jdbc:fake"), host.seenEnvironment);
        assertEquals("FAKE_REQUIREMENT_V1", report.environment().get("oracle.revision"));
        assertTrue(report.cleanup().proved());
        assertEquals(List.of("process exited", "directory removed"), report.cleanup().checks());
    }

    @Test void theApplicationLogsComeFromTheStopAndBelongToTheStartLevel() throws Exception {
        Fixture f = project();
        var host = new FakeHost();
        var log = new io.kcg.sir.application.api.LogRef(io.kcg.sir.application.api.LogRef.Kind.APPLICATION, "logs/application.log", 12, "a".repeat(64));
        host.stopLogs = List.of(log);
        var report = full(new FakeSandbox(), new FakeBuilder(), host, new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));

        assertEquals(List.of(log), report.stage(VerificationStage.START).outcome().logs());
        assertTrue(report.canonicalText().contains("log=START|APPLICATION|logs/application.log|12|"));
        assertEquals(List.of(), report.stage(VerificationStage.BUSINESS).outcome().logs(), "business diagnostics are kept apart from the application log");
    }

    @Test void aRejectingOracleFailsOnlyTheBusinessLevel() throws Exception {
        Fixture f = project();
        var oracle = new FakeOracle();
        oracle.result = StageOutcome.failed("BUSINESS_ASSERTION_FAILED", "page one returned 6 courses, expected 4", List.of("total=6"), List.of());
        var report = full(new FakeSandbox(), new FakeBuilder(), new FakeHost(), oracle).verify(request(f, VerificationStage.BUSINESS));

        assertStages(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED, StageStatus.FAILED);
        assertEquals(List.of("total=6"), report.stage(VerificationStage.BUSINESS).outcome().diagnostics());
        assertTrue(report.cleanup().proved());
        assertFalse(report.passedThrough(VerificationStage.BUSINESS));
        assertTrue(report.passedThrough(VerificationStage.START));
    }

    @Test void aFailedBuildStopsTheLevelsAfterItButStillReleases() throws Exception {
        Fixture f = project();
        var builder = new FakeBuilder();
        builder.result = StageOutcome.failed("BUILD_EXIT_NONZERO", "mvn exited with 1");
        var host = new FakeHost();
        var report = full(new FakeSandbox(), builder, host, new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));

        assertStages(report, StageStatus.PASSED, StageStatus.FAILED, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals("PREVIOUS_STAGE_NOT_PASSED", report.stage(VerificationStage.START).outcome().code());
        assertEquals(List.of("open(true)", "build", "release"), events);
        assertNull(host.seenEnvironment);
    }

    @Test void anUnavailableDatabaseKeepsTheBuildEvidenceAndReportsNotRunNotFailed() throws Exception {
        Fixture f = project();
        var sandbox = new FakeSandbox();
        sandbox.noDatabase = new Sandbox.Unavailable("DATABASE_UNREACHABLE", "the reference database did not answer");
        var host = new FakeHost();
        var report = full(sandbox, new FakeBuilder(), host, new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));

        assertStages(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals("DATABASE_UNREACHABLE", report.stage(VerificationStage.START).outcome().code());
        assertEquals("PREVIOUS_STAGE_NOT_PASSED", report.stage(VerificationStage.BUSINESS).outcome().code());
        assertEquals(List.of("open(true)", "open(false)", "build", "release"), events);
        assertNull(host.seenEnvironment);
    }

    @Test void whenNothingCanBeOpenedTheBuildIsNotRunWithThePortsReason() throws Exception {
        Fixture f = project();
        var sandbox = new FakeSandbox();
        sandbox.nothing = new Sandbox.Unavailable("WORK_AREA_UNAVAILABLE", "no owned work area");
        var report = full(sandbox, new FakeBuilder(), new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.BUILD));

        assertStages(report, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals("WORK_AREA_UNAVAILABLE", report.stage(VerificationStage.BUILD).outcome().code());
        assertEquals(List.of("open(false)"), events);
        assertEquals(CleanupProof.nothingToClean(), report.cleanup());
    }

    @Test void aPortThatThrowsFailsItsLevelWithoutLeakingTheMessageAndStillCleansUp() throws Exception {
        Fixture f = project();
        var oracle = new FakeOracle();
        oracle.throwing = true;
        var report = full(new FakeSandbox(), new FakeBuilder(), new FakeHost(), oracle).verify(request(f, VerificationStage.BUSINESS));

        assertStages(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED, StageStatus.FAILED);
        assertEquals("PORT_EXCEPTION", report.stage(VerificationStage.BUSINESS).outcome().code());
        assertFalse(report.canonicalText().contains("s3cret"), "an exception text may carry a secret the port did not redact");
        assertEquals(List.of("open(true)", "build", "start", "check", "stop", "release"), events);

        events.clear();
        var builder = new FakeBuilder();
        builder.throwing = true;
        var thrown = full(new FakeSandbox(), builder, new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));
        assertEquals("PORT_EXCEPTION", thrown.stage(VerificationStage.BUILD).outcome().code());
        assertEquals(List.of("open(true)", "build", "release"), events);
    }

    @Test void aPortThatIsNotConfiguredIsNotRunNeverPassed() throws Exception {
        Fixture f = project();
        var noHost = new CandidateVerificationApplication(new FakeSandbox(), new FakeBuilder(), null, null, ResourceLimits.defaults())
                .verify(request(f, VerificationStage.BUSINESS));
        assertStages(noHost, StageStatus.PASSED, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals("PORT_NOT_CONFIGURED", noHost.stage(VerificationStage.START).outcome().code());

        var noSandbox = new CandidateVerificationApplication(null, null, null, null, ResourceLimits.defaults())
                .verify(request(f, VerificationStage.START));
        assertStages(noSandbox, StageStatus.PASSED, StageStatus.NOT_RUN, StageStatus.NOT_RUN, StageStatus.NOT_RUN);
        assertEquals("PORT_NOT_CONFIGURED", noSandbox.stage(VerificationStage.BUILD).outcome().code());
    }

    @Test void aBuildThatClaimsSuccessWithoutAnArtifactInsideTheCandidateIsAFailure() throws Exception {
        Fixture f = project();
        var missing = new FakeBuilder();
        missing.writeArtifact = false;
        var report = full(new FakeSandbox(), missing, new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.START));
        assertEquals("BUILD_ARTIFACT_MISSING", report.stage(VerificationStage.BUILD).outcome().code());

        var outside = new FakeBuilder();
        Path elsewhere = temp.resolve("elsewhere.jar");
        Files.writeString(elsewhere, "jar");
        outside.artifactOverride = elsewhere;
        var escaped = full(new FakeSandbox(), outside, new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.START));
        assertEquals("BUILD_ARTIFACT_MISSING", escaped.stage(VerificationStage.BUILD).outcome().code());
        assertEquals(StageStatus.NOT_RUN, escaped.stage(VerificationStage.START).status());
    }

    @Test void aCandidateDirectoryThatIsNotEmptyIsRefusedBeforeAnythingIsWritten() throws Exception {
        Fixture f = project();
        var sandbox = new FakeSandbox();
        sandbox.prefill = true;
        var builder = new FakeBuilder();
        var report = full(sandbox, builder, new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.BUILD));

        assertEquals("MATERIALIZATION_FAILED", report.stage(VerificationStage.BUILD).outcome().code());
        assertNull(builder.seen, "the build must not run on a directory that is not exactly the generated files");
        assertEquals(List.of("open(false)", "release"), events);
        assertEquals("left over", Files.readString(temp.resolve("candidate-1/stray.txt")), "the stray file is left untouched");
    }

    // ------------------------------------------------------------------ cleanup and report

    @Test void cleanupThatCannotBeProvedIsReportedAndNeverHidden() throws Exception {
        Fixture f = project();
        var host = new FakeHost();
        host.stopThrows = true;
        var report = full(new FakeSandbox(), new FakeBuilder(), host, new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));

        assertStages(report, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED, StageStatus.PASSED);
        assertFalse(report.cleanup().proved(), "an application that could not be proved stopped taints the report");
        assertTrue(report.cleanup().checks().get(0).startsWith("application: "), report.cleanup().checks().toString());
        assertEquals(List.of("open(true)", "build", "start", "check", "stop", "release"), events, "the sandbox is still released");

        events.clear();
        var sandbox = new FakeSandbox();
        sandbox.release = CleanupProof.notProved("schema still exists");
        var leaked = full(sandbox, new FakeBuilder(), new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));
        assertFalse(leaked.cleanup().proved());
        assertTrue(leaked.cleanup().checks().contains("schema still exists"));
    }

    @Test void theReportDigestIgnoresDurationsAndChangesWithEvidence() throws Exception {
        Fixture f = project();
        var app = full(new FakeSandbox(), new FakeBuilder(), new FakeHost(), new FakeOracle());
        var first = app.verify(request(f, VerificationStage.BUSINESS));
        var second = app.verify(request(f, VerificationStage.BUSINESS));
        assertEquals(first.sha256Hex(), second.sha256Hex(), "same candidate and same outcomes give the same digest");

        var oracle = new FakeOracle();
        oracle.result = StageOutcome.failed("BUSINESS_ASSERTION_FAILED", "wrong");
        var different = full(new FakeSandbox(), new FakeBuilder(), new FakeHost(), oracle).verify(request(f, VerificationStage.BUSINESS));
        assertNotEquals(first.sha256Hex(), different.sha256Hex());
        assertTrue(first.canonicalText().startsWith("KCG-VERIFICATION-REPORT-V1\n"));
        assertFalse(first.canonicalText().toLowerCase().contains("elapsed"));
    }

    @Test void aReportCannotClaimALevelPassedAfterAnEarlierOneDidNot() throws Exception {
        Fixture f = project();
        var report = full(new FakeSandbox(), new FakeBuilder(), new FakeHost(), new FakeOracle()).verify(request(f, VerificationStage.BUSINESS));
        var stages = new ArrayList<>(report.stages());
        stages.set(1, new StageResult(VerificationStage.BUILD, StageOutcome.failed("BUILD_EXIT_NONZERO", "x"), 0));
        assertThrows(IllegalArgumentException.class, () -> new VerificationReport(report.id(), report.requested(), report.binding(),
                stages, report.cleanup(), report.environment(), report.limits()));
        assertThrows(IllegalArgumentException.class, () -> new VerificationReport(report.id(), report.requested(), report.binding(),
                List.of(), report.cleanup(), report.environment(), report.limits()));
    }

    @Test void outcomesAndLimitsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new StageOutcome(StageStatus.PASSED, "SOMETHING", "m", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StageOutcome(StageStatus.FAILED, "OK", "m", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> StageOutcome.failed("lower", "m"));
        assertThrows(IllegalArgumentException.class, () -> StageOutcome.failed("X", "m".repeat(StageOutcome.MAX_MESSAGE_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5), 256, 256, 128, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofDays(1), 256, 256, 128, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), 10, 256, 128, 1 << 20));
        assertThrows(IllegalArgumentException.class, () -> new VerificationRequest(" ", CompilationInput.singleFile(SourceId.of("a.sir"), "x"), VerificationStage.STATIC));
        assertDoesNotThrow(ResourceLimits::defaults);
    }
}
