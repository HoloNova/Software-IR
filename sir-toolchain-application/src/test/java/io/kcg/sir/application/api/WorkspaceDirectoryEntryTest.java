package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.api.WorkspaceScenarios.Kind;
import io.kcg.sir.application.api.WorkspaceScenarios.Template;
import io.kcg.sir.application.api.WorkspaceScenarios.Workspace;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The directory form of plan and apply, which is what the command line uses: the candidate is a directory of
 * SIR sources the application reads itself, and an apply names the plan it was shown.
 */
class WorkspaceDirectoryEntryTest {
    private static Template template;
    @TempDir static Path shared;
    @TempDir Path temp;
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

    /** The candidate written as a directory next to the project, as a person would have it. */
    private WorkspaceDirectoryRequest directory(Workspace ws, SourceSnapshot candidate) throws Exception {
        Path root = ws.repository().resolveSibling("candidate-" + this.cases++);
        for (var file : candidate.manifest().files()) {
            Path path = root.resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidate.bytes(file.sourceId()));
        }

        return new WorkspaceDirectoryRequest(ws.repository(), ws.sources(), MultiSourceTestSupport.ENTRY, ws.output(), root);
    }

    private static Map<String, Map<String, String>> state(Workspace ws) throws Exception {
        return Map.of("output", ws.outputTree(), "sources", ws.sourceFiles());
    }

    @Test
    void aCandidateDirectoryPlansExactlyLikeTheSameSourcesInMemory() throws Exception {
        Workspace ws = this.workspace();
        var app = new WorkspaceVersionApplication(new FakeVersionControl());

        var fromDirectory = assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(this.directory(ws, ws.template().candidate())));
        var inMemory = assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(ws.request()));

        assertEquals(inMemory.plan(), fromDirectory.plan());
        assertEquals(inMemory.plan().sha256Hex(), fromDirectory.plan().sha256Hex());
    }

    @Test
    void anApplyNamingTheSeenPlanIsCarriedOutAndTheProjectEqualsAFreshGeneration() throws Exception {
        Workspace ws = this.workspace();
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var request = this.directory(ws, ws.template().candidate());
        var planned = assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(request));

        assertInstanceOf(WorkspaceApplyResult.Applied.class, app.apply(request, planned.plan().sha256Hex()));

        assertEquals(ws.template().candidateOutput(), ws.outputTree());
        assertEquals(WorkspaceScenarios.files(ws.template().candidate()), ws.sourceFiles());
        assertEquals(2, git.snapshotCalls());
    }

    @Test
    void aPlanIdThatIsNotTheCurrentPlanIsRefusedAndNothingChanges() throws Exception {
        Workspace ws = this.workspace();
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var request = this.directory(ws, ws.template().candidate());
        var before = state(ws);

        var failure = assertInstanceOf(WorkspaceApplyResult.Failure.class, app.apply(request, "0".repeat(64)));
        assertEquals("SIR-APP-WORKSPACE-VERIFY-001", failure.diagnostics().get(failure.diagnostics().size() - 1).code());
        assertEquals(before, state(ws));
        assertEquals(0, git.calls.size(), "a refused plan id must not reach Git");

        var malformed = assertInstanceOf(WorkspaceApplyResult.Failure.class, app.apply(request, "not-a-plan-id"));
        assertEquals("SIR-APP-WORKSPACE-REQUEST-001", malformed.diagnostics().get(0).code());
        assertEquals(before, state(ws));
    }

    @Test
    void nothingToDoIsReportedWhateverPlanIdIsGiven() throws Exception {
        Workspace ws = this.workspace();
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var same = this.directory(ws, ws.template().base());

        assertInstanceOf(WorkspacePlanningResult.NoChanges.class, app.plan(same));
        assertInstanceOf(WorkspaceApplyResult.NoChanges.class, app.apply(same, "0".repeat(64)));
        assertEquals(0, git.snapshotCalls());
    }

    @Test
    void aCandidateDirectoryThatCannotBeReadOrRootsThatDoNotFitAreRefusedWithAReason() throws Exception {
        Workspace ws = this.workspace();
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        var before = state(ws);

        var missing = new WorkspaceDirectoryRequest(ws.repository(), ws.sources(), MultiSourceTestSupport.ENTRY, ws.output(), this.temp.resolve("no-such-candidate"));
        var unreadable = assertInstanceOf(WorkspacePlanningResult.Failure.class, app.plan(missing));
        assertTrue(unreadable.diagnostics().stream().anyMatch(ExecutionDiagnostic::isError));

        var outside = new WorkspaceDirectoryRequest(ws.repository(), ws.sources(), MultiSourceTestSupport.ENTRY, this.temp.resolve("elsewhere"), this.directory(ws, ws.template().candidate()).candidateRoot());
        var misplaced = assertInstanceOf(WorkspacePlanningResult.Failure.class, app.plan(outside));
        assertEquals("SIR-APP-WORKSPACE-REQUEST-001", misplaced.diagnostics().get(0).code());
        assertInstanceOf(WorkspaceApplyResult.Failure.class, app.apply(outside, "0".repeat(64)));
        assertEquals(before, state(ws));
    }
}
