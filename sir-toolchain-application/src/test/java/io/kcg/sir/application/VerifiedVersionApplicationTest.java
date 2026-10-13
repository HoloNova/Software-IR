package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.CandidateVerificationApplication;
import io.kcg.sir.application.api.CleanupProof;
import io.kcg.sir.application.api.CompilationInput;
import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.ResourceLimits;
import io.kcg.sir.application.api.StageOutcome;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VerificationPorts;
import io.kcg.sir.application.api.VerificationReport;
import io.kcg.sir.application.api.VerificationRequest;
import io.kcg.sir.application.api.VerificationStage;
import io.kcg.sir.application.api.VerifiedVersionApplication;
import io.kcg.sir.application.api.VerifiedVersionApplication.Result;
import io.kcg.sir.application.api.VersionApplyRequest;
import io.kcg.sir.application.api.VersionApplyResult;
import io.kcg.sir.application.api.VersionPlanningApplication;
import io.kcg.sir.application.api.VersionPlanningRequest;
import io.kcg.sir.application.api.VersionPlanningResult;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Verify, then update" is a gate in front of the ordinary version apply. The evidence comes from a real
 * verification run (static generation for real, fake ports above it), and the end state is compared with a
 * from-scratch generation of the candidate by the production toolchain.
 */
class VerifiedVersionApplicationTest {
    private static final VersionPlanningApplication PLANNER = new VersionPlanningApplication();
    private static final VerifiedVersionApplication GATE = new VerifiedVersionApplication();

    @TempDir Path temp;
    private int counter;

    private record Stored(Path output, Path state, SourceSnapshot sources, String baselineId) {}

    private Stored stored() throws Exception {
        Path source = MultiSourceTestSupport.fixture(temp);
        Path output = temp.resolve("output");
        ProjectToolchainResult.Success generated = MultiSourceTestSupport.generate(source, output);
        Path state = temp.resolve("state");
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class,
                new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), output, state)));
        return new Stored(output, state, generated.sources(), saved.receipt().baselineId());
    }

    private static SourceSnapshot withText(SourceSnapshot base, String from, String to) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        boolean replaced = false;
        for (var file : base.manifest().files()) {
            String text = new String(base.bytes(file.sourceId()), StandardCharsets.UTF_8);
            if (!replaced && text.contains(from)) {
                text = text.replace(from, to);
                replaced = true;
            }

            inputs.put(file.sourceId(), text.getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(replaced, "fixture changed: " + from);
        return new SourceSnapshot(base.entry(), inputs);
    }

    private SourceSnapshot widerCode(Stored stored) {
        return withText(stored.sources(), "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);");
    }

    private SourceSnapshot anotherCandidate(Stored stored) {
        return withText(stored.sources(), "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 48);");
    }

    private VersionApplyRequest change(Stored stored, SourceSnapshot candidate) {
        var planning = new VersionPlanningRequest(stored.state(), stored.output(), stored.baselineId(), candidate);
        return new VersionApplyRequest(planning, PLANNER.plan(planning));
    }

    /** A real verification of {@code candidate}: static for real, and ports above it that pass or fail as told. */
    private VerificationReport verify(SourceSnapshot candidate, VerificationStage through, boolean businessPasses, boolean cleanIsProved) {
        var sandbox = new VerificationPorts.Sandbox() {
            @Override public Lease open(boolean needsDatabase) {
                Path dir = temp.resolve("verify-" + (++counter));
                try {
                    Files.createDirectories(dir.resolve("candidate"));
                    Files.createDirectories(dir.resolve("evidence"));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }

                return new Lease() {
                    @Override public Path candidateRoot() { return dir.resolve("candidate"); }
                    @Override public Path evidenceRoot() { return dir.resolve("evidence"); }
                    @Override public Map<String, String> applicationEnvironment() { return Map.of(); }
                    @Override public CleanupProof release() { return cleanIsProved ? CleanupProof.proved("removed") : CleanupProof.notProved("left behind"); }
                };
            }
            @Override public Map<String, String> environment() { return Map.of(); }
        };
        VerificationPorts.Builder builder = new VerificationPorts.Builder() {
            @Override public VerificationPorts.BuildOutcome build(Path root, Path evidence, ResourceLimits limits) {
                try {
                    Files.createDirectories(root.resolve("target"));
                    Files.writeString(root.resolve("target/app.jar"), "jar");
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }

                return new VerificationPorts.BuildOutcome(StageOutcome.passed("built"), Optional.of(root.resolve("target/app.jar")));
            }
            @Override public Map<String, String> environment() { return Map.of(); }
        };
        VerificationPorts.Host host = new VerificationPorts.Host() {
            @Override public VerificationPorts.StartOutcome start(Path artifact, Map<String, String> env, Path evidence, ResourceLimits limits) {
                return new VerificationPorts.StartOutcome(StageOutcome.passed("ready"), Optional.of(new VerificationPorts.Running() {
                    @Override public URI baseUrl() { return URI.create("http://127.0.0.1:1"); }
                    @Override public VerificationPorts.Stopped stop() { return new VerificationPorts.Stopped(CleanupProof.proved("exited"), List.of()); }
                }));
            }
            @Override public Map<String, String> environment() { return Map.of(); }
        };
        VerificationPorts.Oracle oracle = new VerificationPorts.Oracle() {
            @Override public String revision() { return "TEST_PACK_V1"; }
            @Override public StageOutcome check(URI baseUrl, Path evidence, ResourceLimits limits) {
                return businessPasses ? StageOutcome.passed("holds") : StageOutcome.failed("BUSINESS_ASSERTION_FAILED", "wrong rows");
            }
        };
        return new CandidateVerificationApplication(sandbox, builder, host, oracle, ResourceLimits.defaults())
                .verify(new VerificationRequest("candidate", CompilationInput.project(candidate), through));
    }

    private Map<String, String> fromScratch(SourceSnapshot candidate) throws Exception {
        Path sources = temp.resolve("scratch-sources");
        for (var file : candidate.manifest().files()) {
            Path path = sources.resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidate.bytes(file.sourceId()));
        }

        Path output = temp.resolve("scratch-output");
        assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(sources, candidate.entry(), output)));
        return MultiSourceTestSupport.files(output);
    }

    private record Snapshot(Map<String, String> state, Map<String, String> output) {}

    private Snapshot untouched(Stored stored) throws Exception {
        return new Snapshot(MultiSourceTestSupport.tree(stored.state()), MultiSourceTestSupport.tree(stored.output()));
    }

    private void assertRefused(Result result, String code) {
        assertEquals(code, assertInstanceOf(Result.Refused.class, result).code(), result.toString());
    }

    @Test void aVerifiedCandidateIsPublishedAndTheProjectEqualsItsFromScratchGeneration() throws Exception {
        Stored stored = stored();
        SourceSnapshot candidate = widerCode(stored);
        var report = verify(candidate, VerificationStage.BUSINESS, true, true);
        assertTrue(report.passedThrough(VerificationStage.BUSINESS));

        var result = GATE.apply(new VerifiedVersionApplication.Request(change(stored, candidate), report, VerificationStage.BUSINESS));

        var applied = assertInstanceOf(VersionApplyResult.Applied.class, assertInstanceOf(Result.Completed.class, result).result());
        assertEquals(stored.baselineId(), applied.base().baselineId());
        assertEquals(report.binding().orElseThrow().manifestDigest(), applied.candidate().manifestDigest(), "the published version is the verified one");
        assertEquals(fromScratch(candidate), MultiSourceTestSupport.files(stored.output()));
    }

    @Test void theCallerChoosesTheBarAndAHigherBarThanTheEvidenceIsRefusedUntouched() throws Exception {
        Stored stored = stored();
        SourceSnapshot candidate = widerCode(stored);
        var wrongBusiness = verify(candidate, VerificationStage.BUSINESS, false, true);
        Snapshot before = untouched(stored);

        assertRefused(GATE.apply(new VerifiedVersionApplication.Request(change(stored, candidate), wrongBusiness, VerificationStage.BUSINESS)), "VERIFICATION_NOT_PASSED");
        assertEquals(before, untouched(stored), "a refusal must not read or change the project or its state");

        var staticOnly = verify(candidate, VerificationStage.STATIC, true, true);
        var refused = assertInstanceOf(Result.Refused.class,
                GATE.apply(new VerifiedVersionApplication.Request(change(stored, candidate), staticOnly, VerificationStage.BUILD)));
        assertEquals("VERIFICATION_NOT_PASSED", refused.code());
        assertTrue(refused.message().contains("BUILD is NOT_RUN (NOT_REQUESTED)"), refused.message());
        assertEquals(before, untouched(stored));

        // The same failing-business report is enough when only a start is required.
        var started = GATE.apply(new VerifiedVersionApplication.Request(change(stored, candidate), wrongBusiness, VerificationStage.START));
        assertInstanceOf(VersionApplyResult.Applied.class, assertInstanceOf(Result.Completed.class, started).result());
    }

    @Test void evidenceAboutAnotherCandidateIsRefused() throws Exception {
        Stored stored = stored();
        var forOther = verify(anotherCandidate(stored), VerificationStage.BUSINESS, true, true);
        Snapshot before = untouched(stored);

        assertRefused(GATE.apply(new VerifiedVersionApplication.Request(change(stored, widerCode(stored)), forOther, VerificationStage.BUSINESS)), "VERIFICATION_CANDIDATE_MISMATCH");
        assertEquals(before, untouched(stored));
    }

    @Test void aVerificationThatCouldNotProveItsCleanupIsNotAcceptedAsEvidence() throws Exception {
        Stored stored = stored();
        SourceSnapshot candidate = widerCode(stored);
        var dirty = verify(candidate, VerificationStage.BUSINESS, true, false);
        Snapshot before = untouched(stored);

        assertTrue(dirty.passedThrough(VerificationStage.BUSINESS));
        assertRefused(GATE.apply(new VerifiedVersionApplication.Request(change(stored, candidate), dirty, VerificationStage.BUSINESS)), "VERIFICATION_CLEANUP_NOT_PROVED");
        assertEquals(before, untouched(stored));
    }

    @Test void aVerificationOfSourcesThatDoNotGenerateIsEvidenceAboutNoCandidate() throws Exception {
        Stored stored = stored();
        SourceSnapshot candidate = widerCode(stored);
        SourceSnapshot broken = withText(stored.sources(), "field code: String where notBlank, length(1, 32);", "field code: NoSuchType where notBlank;");
        var failedStatic = verify(broken, VerificationStage.STATIC, true, true);
        Snapshot before = untouched(stored);

        assertFalse(failedStatic.passedThrough(VerificationStage.STATIC));
        assertRefused(GATE.apply(new VerifiedVersionApplication.Request(change(stored, candidate), failedStatic, VerificationStage.STATIC)), "VERIFICATION_NOT_BOUND");
        assertEquals(before, untouched(stored));
    }

    @Test void whenNothingChangesThereIsNothingToVerifyAndNothingIsPublished() throws Exception {
        Stored stored = stored();
        var change = change(stored, stored.sources());
        assertInstanceOf(VersionPlanningResult.NoChanges.class, change.expected());
        Snapshot before = untouched(stored);

        var result = GATE.apply(new VerifiedVersionApplication.Request(change, verify(stored.sources(), VerificationStage.STATIC, true, true), VerificationStage.BUSINESS));

        assertInstanceOf(VersionApplyResult.NoChanges.class, assertInstanceOf(Result.Completed.class, result).result());
        assertEquals(before, untouched(stored));
    }

    @Test void acceptedEvidenceNeverOverridesTheOrdinaryChecksSoAStalePlanStaysRefused() throws Exception {
        Stored stored = stored();
        SourceSnapshot candidate = widerCode(stored);
        var report = verify(candidate, VerificationStage.BUSINESS, true, true);
        var request = new VerifiedVersionApplication.Request(change(stored, candidate), report, VerificationStage.BUSINESS);
        assertInstanceOf(VersionApplyResult.Applied.class, assertInstanceOf(Result.Completed.class, GATE.apply(request)).result());
        Snapshot afterFirst = untouched(stored);

        // The same report and the same plan again: the stored version has moved on, so the ordinary apply refuses.
        var again = assertInstanceOf(Result.Completed.class, GATE.apply(request));
        assertInstanceOf(VersionApplyResult.Failure.class, again.result());
        assertEquals(afterFirst, untouched(stored));
    }
}
