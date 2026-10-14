package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.VerifiedWorkspaceApplication.Result;
import io.kcg.sir.application.api.WorkspaceScenarios.Kind;
import io.kcg.sir.application.api.WorkspaceScenarios.Template;
import io.kcg.sir.application.api.WorkspaceScenarios.Workspace;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Verify, then update" for the Git-based path is the same gate as for the older version path, in front of the
 * workspace apply. The evidence comes from a real verification run (static generation for real, fake ports above
 * it), and the end state is compared with a from-scratch generation of the candidate by the production toolchain.
 */
class VerifiedWorkspaceApplicationTest {
    private static Template template;
    @TempDir static Path shared;
    @TempDir Path temp;
    private final AtomicInteger counter = new AtomicInteger();
    private int cases;

    private static synchronized Template template() throws Exception {
        if (template == null) {
            template = WorkspaceScenarios.template(Files.createDirectories(shared.resolve("MIXED")), Kind.MIXED);
        }

        return template;
    }

    private Workspace workspace() throws Exception {
        return template().copy(this.temp.resolve("case" + this.cases++));
    }

    private VerificationReport verify(SourceSnapshot candidate, VerificationStage through, boolean businessPasses, boolean cleanIsProved) {
        return VerificationFixtures.verify(this.temp, this.counter, candidate, through, businessPasses, cleanIsProved);
    }

    private static SourceSnapshot withCodeLength(Template template, String replacement) {
        return WorkspaceScenarios.edit(template.base(), new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", replacement});
    }

    private static Map<String, Map<String, String>> state(Workspace ws) throws Exception {
        return Map.of("output", ws.outputTree(), "sources", ws.sourceFiles());
    }

    private static void assertRefused(Result result, String code) {
        assertEquals(code, assertInstanceOf(Result.Refused.class, result).code(), result.toString());
    }

    private VerifiedWorkspaceApplication.Request request(Workspace ws, WorkspaceVersionApplication app, SourceSnapshot candidate, VerificationReport report, VerificationStage required) {
        var request = ws.request(candidate);
        return new VerifiedWorkspaceApplication.Request(request, app.plan(request), report, required);
    }

    @Test
    void aVerifiedCandidateIsPublishedAndTheProjectEqualsItsFromScratchGeneration() throws Exception {
        Workspace ws = this.workspace();
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var report = this.verify(ws.template().candidate(), VerificationStage.BUSINESS, true, true);
        assertTrue(report.passedThrough(VerificationStage.BUSINESS));

        var result = new VerifiedWorkspaceApplication(app).apply(this.request(ws, app, ws.template().candidate(), report, VerificationStage.BUSINESS));

        var applied = assertInstanceOf(WorkspaceApplyResult.Applied.class, assertInstanceOf(Result.Completed.class, result).result());
        assertEquals(report.binding().orElseThrow().manifestDigest(), applied.candidate().manifestDigest(), "the published version is the verified one");
        assertEquals(ws.template().candidateOutput(), ws.outputTree());
        assertEquals(WorkspaceScenarios.files(ws.template().candidate()), ws.sourceFiles());
        assertEquals(2, git.snapshotCalls());
    }

    @Test
    void theCallerChoosesTheBarAndAHigherBarThanTheEvidenceIsRefusedUntouched() throws Exception {
        Workspace ws = this.workspace();
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var gate = new VerifiedWorkspaceApplication(app);
        SourceSnapshot candidate = ws.template().candidate();
        var wrongBusiness = this.verify(candidate, VerificationStage.BUSINESS, false, true);
        var before = state(ws);

        assertRefused(gate.apply(this.request(ws, app, candidate, wrongBusiness, VerificationStage.BUSINESS)), "VERIFICATION_NOT_PASSED");
        assertEquals(before, state(ws), "a refusal must not read or change the project or its sources");
        assertEquals(0, git.calls.size(), "a refusal must not even ask Git");

        var staticOnly = this.verify(candidate, VerificationStage.STATIC, true, true);
        var refused = assertInstanceOf(Result.Refused.class, gate.apply(this.request(ws, app, candidate, staticOnly, VerificationStage.BUILD)));
        assertEquals("VERIFICATION_NOT_PASSED", refused.code());
        assertTrue(refused.message().contains("BUILD is NOT_RUN (NOT_REQUESTED)"), refused.message());
        assertEquals(before, state(ws));

        // The same failing-business report is enough when only a start is required.
        var started = gate.apply(this.request(ws, app, candidate, wrongBusiness, VerificationStage.START));
        assertInstanceOf(WorkspaceApplyResult.Applied.class, assertInstanceOf(Result.Completed.class, started).result());
    }

    @Test
    void evidenceAboutAnotherCandidateIsRefused() throws Exception {
        Workspace ws = this.workspace();
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        var forOther = this.verify(withCodeLength(ws.template(), "field code: String where notBlank, length(1, 48);"), VerificationStage.BUSINESS, true, true);
        var before = state(ws);

        assertRefused(new VerifiedWorkspaceApplication(app).apply(this.request(ws, app, ws.template().candidate(), forOther, VerificationStage.BUSINESS)), "VERIFICATION_CANDIDATE_MISMATCH");
        assertEquals(before, state(ws));
    }

    @Test
    void aVerificationThatCouldNotProveItsCleanupIsNotAcceptedAsEvidence() throws Exception {
        Workspace ws = this.workspace();
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        SourceSnapshot candidate = ws.template().candidate();
        var dirty = this.verify(candidate, VerificationStage.BUSINESS, true, false);
        var before = state(ws);

        assertTrue(dirty.passedThrough(VerificationStage.BUSINESS));
        assertRefused(new VerifiedWorkspaceApplication(app).apply(this.request(ws, app, candidate, dirty, VerificationStage.BUSINESS)), "VERIFICATION_CLEANUP_NOT_PROVED");
        assertEquals(before, state(ws));
    }

    @Test
    void aVerificationOfSourcesThatDoNotGenerateIsEvidenceAboutNoCandidate() throws Exception {
        Workspace ws = this.workspace();
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        SourceSnapshot broken = withCodeLength(ws.template(), "field code: NoSuchType where notBlank;");
        var failedStatic = this.verify(broken, VerificationStage.STATIC, true, true);
        var before = state(ws);

        assertFalse(failedStatic.passedThrough(VerificationStage.STATIC));
        assertRefused(new VerifiedWorkspaceApplication(app).apply(this.request(ws, app, ws.template().candidate(), failedStatic, VerificationStage.STATIC)), "VERIFICATION_NOT_BOUND");
        assertEquals(before, state(ws));
    }

    @Test
    void whenNothingChangesThereIsNothingToVerifyAndNothingIsPublished() throws Exception {
        Workspace ws = this.workspace();
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        SourceSnapshot same = ws.template().base();
        var request = this.request(ws, app, same, this.verify(same, VerificationStage.STATIC, true, true), VerificationStage.BUSINESS);
        assertInstanceOf(WorkspacePlanningResult.NoChanges.class, request.expected());
        var before = state(ws);

        var result = new VerifiedWorkspaceApplication(app).apply(request);

        assertInstanceOf(WorkspaceApplyResult.NoChanges.class, assertInstanceOf(Result.Completed.class, result).result());
        assertEquals(before, state(ws));
        assertEquals(0, git.snapshotCalls());
    }

    @Test
    void acceptedEvidenceNeverOverridesTheOrdinaryChecksSoAStalePlanStaysRefused() throws Exception {
        Workspace ws = this.workspace();
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        var gate = new VerifiedWorkspaceApplication(app);
        SourceSnapshot candidate = ws.template().candidate();
        var request = this.request(ws, app, candidate, this.verify(candidate, VerificationStage.BUSINESS, true, true), VerificationStage.BUSINESS);
        assertInstanceOf(WorkspaceApplyResult.Applied.class, assertInstanceOf(Result.Completed.class, gate.apply(request)).result());
        var afterFirst = state(ws);

        // The same report and the same plan again: the project has moved on, so the ordinary apply refuses.
        var again = assertInstanceOf(Result.Completed.class, gate.apply(request));
        assertInstanceOf(WorkspaceApplyResult.Failure.class, again.result());
        assertEquals(afterFirst, state(ws));
    }
}
