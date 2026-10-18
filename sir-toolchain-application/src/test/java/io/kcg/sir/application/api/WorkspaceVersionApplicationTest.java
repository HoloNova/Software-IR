package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kcg.sir.application.ProjectTestSupport;
import io.kcg.sir.application.api.WorkspaceScenarios.Kind;
import io.kcg.sir.application.api.WorkspaceScenarios.Template;
import io.kcg.sir.application.api.WorkspaceScenarios.Workspace;
import io.kcg.sir.application.execution.GitVersionControl;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A change against the SIR on disk, with Git as the history (ADR-027). The expected result of every change is
 * produced independently: the production toolchain generates the candidate from scratch, and the project after
 * the change must equal it byte for byte, directory tree included.
 */
class WorkspaceVersionApplicationTest {
    private static final Map<Kind, Template> TEMPLATES = new EnumMap<>(Kind.class);
    @TempDir static Path shared;
    @TempDir Path temp;
    private int cases;

    private static synchronized Template template(Kind kind) throws Exception {
        Template template = TEMPLATES.get(kind);
        if (template == null) {
            template = WorkspaceScenarios.template(Files.createDirectories(shared.resolve(kind.name())), kind);
            TEMPLATES.put(kind, template);
        }

        return template;
    }

    private Workspace workspace(Kind kind) throws Exception {
        return template(kind).copy(this.temp.resolve("case" + this.cases++));
    }

    /** Everything on disk that a change may touch, to prove a refusal changed nothing. */
    private static Map<String, Map<String, String>> state(Workspace ws) throws Exception {
        return Map.of("output", ws.outputTree(), "sources", ws.sourceFiles());
    }

    private static void assertIsCandidate(Workspace ws) throws Exception {
        assertEquals(ws.template().candidateOutput(), ws.outputTree(), "the generated project must equal a fresh generation of the candidate");
        assertEquals(WorkspaceScenarios.files(ws.template().candidate()), ws.sourceFiles(), "the sources on disk must be the candidate");
    }

    private static WorkspaceApplyResult applyPlanned(WorkspaceVersionApplication app, Workspace ws) {
        var planned = assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(ws.request()), "must be plannable");
        return app.apply(ws.request(), planned);
    }

    private static ExecutionDiagnostic firstError(List<ExecutionDiagnostic> diagnostics) {
        return diagnostics.stream().filter(ExecutionDiagnostic::isError).findFirst().orElseThrow();
    }

    // ----------------------------------------------------------------------------- the change itself

    @ParameterizedTest
    @EnumSource(Kind.class)
    void everyKindOfChangeEndsEqualToAFreshGenerationAndIsSavedInGitTwice(Kind kind) throws Exception {
        Workspace ws = this.workspace(kind);
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);

        var applied = assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(app, ws));

        assertIsCandidate(ws);
        assertEquals(List.of("inspect", "snapshot", "snapshot"), git.calls);
        assertTrue(applied.beforeCommit().isPresent() && applied.afterCommit().isPresent());
        assertEquals(new ArrayList<>(this.expectedSnapshotPaths(ws.template())), git.snapshots.get(0));
        assertEquals(git.snapshots.get(0), git.snapshots.get(1));
        assertFalse(Files.exists(ws.output().resolve(".kcg-staging")), "the staging directory must be gone");

        // The version now on disk is the candidate: planning the same change again finds nothing to do.
        var again = assertInstanceOf(WorkspacePlanningResult.NoChanges.class, app.plan(ws.request()));
        assertEquals(applied.candidate(), again.baseline());
        assertInstanceOf(WorkspaceApplyResult.NoChanges.class, app.apply(ws.request(), again));
        assertEquals(2, git.snapshotCalls(), "no change must not be saved again");
    }

    @Test
    void withRealGitTheTwoVersionsAreTwoCommitsAndTheTreeEndsClean() throws Exception {
        assumeGit();
        Workspace ws = this.workspace(Kind.MIXED);
        // No repository exists yet: the application creates it.
        assertFalse(Files.exists(ws.repository().resolve(".git")));
        var app = new WorkspaceVersionApplication(this.realGit());

        var applied = assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(app, ws));

        assertIsCandidate(ws);
        assertEquals(List.of("kcg: save the current version before applying plan", "kcg: apply plan"),
            git(ws, "log", "--reverse", "--format=%s").lines().map(s -> s.replaceAll(" [0-9a-f]{12}.*", "")).toList());
        assertEquals(applied.beforeCommit().orElseThrow(), git(ws, "rev-parse", "HEAD~1").strip());
        assertEquals(applied.afterCommit().orElseThrow(), git(ws, "rev-parse", "HEAD").strip());
        assertEquals("", git(ws, "status", "--porcelain").strip(), "everything the change touched is saved");

        // The previous version in Git is exactly the base, so Git alone can go back to it.
        assertEquals(WorkspaceScenarios.text(ws.template().base(), "project.sir"), git(ws, "show", "HEAD~1:sources/project.sir"));
        assertEquals(WorkspaceScenarios.text(ws.template().candidate(), "project.sir"), git(ws, "show", "HEAD:sources/project.sir"));

        // A repeated apply changes nothing and saves nothing.
        var again = assertInstanceOf(WorkspacePlanningResult.NoChanges.class, app.plan(ws.request()));
        assertInstanceOf(WorkspaceApplyResult.NoChanges.class, app.apply(ws.request(), again));
        assertEquals("2", git(ws, "rev-list", "--count", "HEAD").strip());
    }

    @Test
    void goingBackToTheOldVersionWithGitLeavesAProjectThatPlansCleanly() throws Exception {
        assumeGit();
        Workspace ws = this.workspace(Kind.MIXED);
        var app = new WorkspaceVersionApplication(this.realGit());
        assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(app, ws));

        // What the owner does to go back: Git, not this tool.
        git(ws, "restore", "--source=HEAD~1", "--staged", "--worktree", "--", "sources", "generated");
        git(ws, "clean", "-fdq", "--", "sources", "generated");

        assertEquals(ws.template().baseOutput(), ws.outputTree());
        assertEquals(WorkspaceScenarios.files(ws.template().base()), ws.sourceFiles());
        // The restored project is again a consistent one: the same change can be planned against it.
        assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(ws.request()));
    }

    @Test
    void removingASourceFileIsTheReverseOfAddingIt() throws Exception {
        Workspace ws = this.workspace(Kind.NEW_SOURCE_FILE);
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(app, ws));
        assertTrue(Files.exists(ws.sources().resolve("modules/notes.sir")));

        var back = ws.request(ws.template().base());
        var planned = assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(back));
        assertEquals(1, planned.plan().sources().entries().stream().filter(e -> e.action() == SourceDelta.Action.REMOVED).count());
        assertInstanceOf(WorkspaceApplyResult.Applied.class, app.apply(back, planned));

        assertFalse(Files.exists(ws.sources().resolve("modules/notes.sir")));
        assertEquals(ws.template().baseOutput(), ws.outputTree());
        assertEquals(WorkspaceScenarios.files(ws.template().base()), ws.sourceFiles());
    }

    @Test
    void aFileTheUserKeptInADirectoryTheChangeEmptiesKeepsThatDirectory() throws Exception {
        Workspace ws = this.workspace(Kind.RENAME);
        String gone = ws.template().baseOutput().keySet().stream().filter(k -> k.endsWith("/") && !ws.template().candidateOutput().containsKey(k))
            .max(java.util.Comparator.comparingInt(String::length)).orElseThrow();
        Path kept = ws.output().resolve(gone).resolve("keep-me.txt");
        Files.writeString(kept, "mine");

        assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(new WorkspaceVersionApplication(new FakeVersionControl()), ws));

        assertEquals("mine", Files.readString(kept));
        Map<String, String> expected = new TreeMap<>(ProjectTestSupport.files(ws.template().directory().resolve("expected-output")));
        expected.put(ws.output().relativize(kept).toString().replace('\\', '/'), hex("mine"));
        assertEquals(expected, ProjectTestSupport.files(ws.output()));
    }

    // ------------------------------------------------------------------------------ what is refused

    @Test
    void withoutGitNothingIsChangedAndNothingIsSaved() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        var git = new FakeVersionControl();
        git.state = VersionControl.State.UNAVAILABLE;
        git.detail = "git cannot be started";
        var before = state(ws);

        var failure = assertInstanceOf(WorkspaceApplyResult.Failure.class, applyPlanned(new WorkspaceVersionApplication(git), ws));

        assertEquals("SIR-APP-WORKSPACE-GIT-001", firstError(failure.diagnostics()).code());
        assertTrue(firstError(failure.diagnostics()).message().contains("nothing was changed"));
        assertEquals(before, state(ws));
        assertEquals(0, git.snapshotCalls());
    }

    @Test
    void aDirectoryInsideAnotherRepositoryIsRefusedAndAMissingRepositoryIsCreated() throws Exception {
        Workspace nested = this.workspace(Kind.MIXED);
        var inside = new FakeVersionControl();
        inside.state = VersionControl.State.NOT_TOP_LEVEL;
        var before = state(nested);
        assertInstanceOf(WorkspaceApplyResult.Failure.class, applyPlanned(new WorkspaceVersionApplication(inside), nested));
        assertEquals(before, state(nested));
        assertEquals(0, inside.snapshotCalls());

        Workspace fresh = this.workspace(Kind.MIXED);
        var none = new FakeVersionControl();
        none.state = VersionControl.State.NO_REPOSITORY;
        assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(new WorkspaceVersionApplication(none), fresh));
        assertEquals(List.of("inspect", "initialize", "snapshot", "snapshot"), none.calls);
        assertIsCandidate(fresh);

        Workspace refused = this.workspace(Kind.MIXED);
        var cannot = new FakeVersionControl();
        cannot.state = VersionControl.State.NO_REPOSITORY;
        cannot.initializeFailure = java.util.Optional.of("read-only file system");
        var untouched = state(refused);
        var failure = assertInstanceOf(WorkspaceApplyResult.Failure.class, applyPlanned(new WorkspaceVersionApplication(cannot), refused));
        assertTrue(firstError(failure.diagnostics()).message().contains("read-only file system"));
        assertEquals(untouched, state(refused));
    }

    @Test
    void ifTheCurrentVersionCannotBeSavedNothingIsChanged() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        var git = new FakeVersionControl();
        git.failingSnapshot = 1;
        var before = state(ws);

        var failure = assertInstanceOf(WorkspaceApplyResult.Failure.class, applyPlanned(new WorkspaceVersionApplication(git), ws));

        assertEquals("SIR-APP-WORKSPACE-GIT-002", firstError(failure.diagnostics()).code());
        assertEquals(before, state(ws));
        assertEquals(1, git.snapshotCalls());
    }

    @Test
    void ifTheNewVersionCannotBeSavedTheFilesAreCompleteAndTheWarningSaysSo() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        var git = new FakeVersionControl();
        git.failingSnapshot = 2;

        var applied = assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(new WorkspaceVersionApplication(git), ws));

        assertIsCandidate(ws);
        assertTrue(applied.afterCommit().isEmpty());
        assertTrue(applied.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-APP-WORKSPACE-GIT-003") && d.severity() == ExecutionSeverity.WARNING));
    }

    @Test
    void aPlanThatIsNoLongerWhatTheDiskSaysIsRefused() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var planned = assertInstanceOf(WorkspacePlanningResult.Planned.class, app.plan(ws.request()));

        // Someone edits the SIR after the plan was made.
        Path student = ws.sources().resolve("modules/student.sir");
        Files.writeString(student, Files.readString(student).replace("sir 0.3", "sir 0.3\n// edited after planning"));
        var before = state(ws);

        var failure = assertInstanceOf(WorkspaceApplyResult.Failure.class, app.apply(ws.request(), planned));

        assertEquals("SIR-APP-WORKSPACE-VERIFY-001", firstError(failure.diagnostics()).code());
        assertEquals(before, state(ws));
        assertEquals(0, git.snapshotCalls());
    }

    @Test
    void expectingNoChangesWhenThereAreChangesIsRefused() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        var same = assertInstanceOf(WorkspacePlanningResult.NoChanges.class, app.plan(ws.request(ws.template().base())));
        var before = state(ws);

        assertInstanceOf(WorkspaceApplyResult.Failure.class, app.apply(ws.request(), same));
        assertEquals(before, state(ws));
        assertThrows(IllegalArgumentException.class, () -> app.apply(ws.request(), new WorkspacePlanningResult.Failure(ExecutionStage.PREFLIGHT,
            List.of(ExecutionDiagnostic.error("X", ExecutionStage.PREFLIGHT, "x")))));
    }

    // --------------------------------------------------------- the generated project must be the SIR's

    @Test
    void anEditedGeneratedFileIsRefusedEvenIfTheChangeDoesNotTouchIt() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        String path = this.generatedFile(ws.template(), false);
        Path file = ws.output().resolve(path);
        Files.writeString(file, Files.readString(file) + "\n// hand edit");
        var git = new FakeVersionControl();
        var app = new WorkspaceVersionApplication(git);
        var before = state(ws);

        var failure = assertInstanceOf(WorkspacePlanningResult.Failure.class, app.plan(ws.request()));

        ExecutionDiagnostic drift = firstError(failure.diagnostics());
        assertEquals("SIR-APP-WORKSPACE-DRIFT-001", drift.code());
        assertTrue(drift.message().startsWith(path + ": "), drift.message());
        assertTrue(drift.message().contains("regenerate"), "the user is told what to do");
        assertInstanceOf(WorkspaceApplyResult.Failure.class, app.apply(ws.request(), this.plannedBeforeTheEdit()));
        assertEquals(0, git.snapshotCalls());
        assertEquals(before.get("sources"), state(ws).get("sources"));
    }

    @Test
    void aMissingGeneratedFileIsRefused() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        Files.delete(ws.output().resolve(this.generatedFile(ws.template(), false)));
        var failure = assertInstanceOf(WorkspacePlanningResult.Failure.class, new WorkspaceVersionApplication(new FakeVersionControl()).plan(ws.request()));
        assertTrue(firstError(failure.diagnostics()).message().contains("missing"));
    }

    @Test
    void aFileTheChangeWritesMustHoldTheOldBytesOrTheNewBytes() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        String path = this.generatedFile(ws.template(), true);
        Path file = ws.output().resolve(path);
        Files.writeString(file, "neither the old nor the new version");
        var app = new WorkspaceVersionApplication(new FakeVersionControl());
        assertInstanceOf(WorkspacePlanningResult.Failure.class, app.plan(ws.request()));

        // The new bytes are what an interrupted earlier run leaves behind; that is acceptable and gets finished.
        Files.write(file, Files.readAllBytes(ws.template().directory().resolve("expected-output").resolve(path)));
        assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(app, ws));
        assertIsCandidate(ws);
    }

    @Test
    void aForeignDirectoryNamedLikeTheStagingAreaStopsTheChangeBeforeItStarts() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        Path staging = Files.createDirectory(ws.output().resolve(".kcg-staging"));
        Files.writeString(staging.resolve("mine.txt"), "not ours");
        var before = state(ws);

        var failure = assertInstanceOf(WorkspaceApplyResult.Failure.class, applyPlanned(new WorkspaceVersionApplication(new FakeVersionControl()), ws));

        assertEquals("not ours", Files.readString(staging.resolve("mine.txt")));
        assertTrue(firstError(failure.diagnostics()).message().contains("did not write"), firstError(failure.diagnostics()).message());
        Map<String, Map<String, String>> after = state(ws);
        assertEquals(before.get("sources"), after.get("sources"));
        assertEquals(before.get("output"), after.get("output"));
    }

    @Test
    void theRootsMustBeAbsoluteInsideTheRepositoryAndSeparate() throws Exception {
        Workspace ws = this.workspace(Kind.MIXED);
        SourceSnapshot candidate = ws.template().candidate();
        Path repo = ws.repository();
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceVersionRequest(Path.of("repo"), ws.sources(), ws.output(), candidate));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceVersionRequest(repo, ws.sources(), repo.getParent().resolve("elsewhere"), candidate));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceVersionRequest(repo, ws.sources(), ws.sources().resolve("generated"), candidate));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceVersionRequest(repo, repo, ws.output(), candidate));
    }

    // ------------------------------------------------------------------------- stopping half way

    @Test
    void aRunStoppedAtAnyPointIsFinishedBySimplyRunningItAgain() throws Exception {
        for (Kind kind : List.of(Kind.MIXED, Kind.RENAME, Kind.TWO_SOURCES, Kind.NEW_SOURCE_FILE)) {
            Template template = template(kind);
            int total = this.checkpointCount(kind);
            assertTrue(total > 10, kind + " has too few stopping points: " + total);
            System.out.println("workspace-stop-matrix kind=" + kind + " points=" + total);
            for (int point : this.samplePoints(total)) {
                Workspace ws = this.workspace(kind);
                int[] seen = {0};
                var git = new FakeVersionControl();
                var stopping = new WorkspaceVersionApplication(git, name -> {
                    if (++seen[0] == point) {
                        throw new IllegalStateException("stopped at " + name);
                    }
                });

                var interrupted = assertInstanceOf(WorkspaceApplyResult.RerunRequired.class, applyPlanned(stopping, ws), kind + " point " + point);
                assertTrue(interrupted.beforeCommit().isPresent());
                assertTrue(firstError(interrupted.diagnostics()).message().contains("Run the same apply again"));
                this.assertNothingHalfWritten(ws, template, kind + " point " + point);

                var finishing = new WorkspaceVersionApplication(new FakeVersionControl());
                switch (finishing.plan(ws.request())) {
                    case WorkspacePlanningResult.Planned planned -> assertInstanceOf(WorkspaceApplyResult.Applied.class, finishing.apply(ws.request(), planned), kind + " point " + point);
                    case WorkspacePlanningResult.NoChanges none -> assertInstanceOf(WorkspaceApplyResult.NoChanges.class, finishing.apply(ws.request(), none), kind + " point " + point);
                    case WorkspacePlanningResult.Failure failure -> fail(kind + " point " + point + " (" + failure.diagnostics() + ")");
                }

                assertIsCandidate(ws);
                assertFalse(Files.exists(ws.output().resolve(".kcg-staging")), kind + " point " + point);
            }
        }
    }

    /** At a stop, every file is exactly the old or the new bytes (or still waiting in staging): never a mixture. */
    private void assertNothingHalfWritten(Workspace ws, Template template, String where) throws Exception {
        Map<String, String> output = ProjectTestSupport.files(ws.output());
        for (var entry : output.entrySet()) {
            if (entry.getKey().startsWith(".kcg-staging/")) {
                continue;
            }

            assertTrue(entry.getValue().equals(template.baseOutput().get(entry.getKey())) || entry.getValue().equals(template.candidateOutput().get(entry.getKey())),
                where + ": a generated file is neither version: " + entry.getKey());
        }

        Map<String, String> base = WorkspaceScenarios.files(template.base());
        Map<String, String> candidate = WorkspaceScenarios.files(template.candidate());
        for (var entry : ws.sourceFiles().entrySet()) {
            assertTrue(entry.getValue().equals(base.get(entry.getKey())) || entry.getValue().equals(candidate.get(entry.getKey())), where + ": a source is neither version: " + entry.getKey());
        }
    }

    private int checkpointCount(Kind kind) throws Exception {
        Workspace ws = this.workspace(kind);
        int[] count = {0};
        var app = new WorkspaceVersionApplication(new FakeVersionControl(), name -> count[0]++);
        assertInstanceOf(WorkspaceApplyResult.Applied.class, applyPlanned(app, ws));
        assertIsCandidate(ws);
        return count[0];
    }

    /** Every stopping point; a system property can thin them out for a quick local run (boundaries are always kept). */
    private List<Integer> samplePoints(int total) {
        int wanted = Integer.getInteger("kcg.workspace.matrix.points", Integer.MAX_VALUE);
        TreeSet<Integer> points = new TreeSet<>();
        if (wanted >= total) {
            for (int i = 1; i <= total; i++) {
                points.add(i);
            }

            return new ArrayList<>(points);
        }

        points.addAll(List.of(1, 2, 3, total - 2, total - 1, total));
        for (int i = 1; i <= wanted; i++) {
            points.add(Math.max(1, i * total / (wanted + 1)));
        }

        return new ArrayList<>(points);
    }

    // ------------------------------------------------------------------------------------- helpers

    private WorkspacePlanningResult.Planned plannedBeforeTheEdit() throws Exception {
        // The plan an owner would hold from before the generated file was edited.
        Workspace pristine = template(Kind.MIXED).copy(this.temp.resolve("pristine" + this.cases++));
        var planned = assertInstanceOf(WorkspacePlanningResult.Planned.class, new WorkspaceVersionApplication(new FakeVersionControl()).plan(pristine.request()));
        return new WorkspacePlanningResult.Planned(planned.plan(), List.of());
    }

    /** A generated file the change leaves alone ({@code touched} false) or rewrites ({@code touched} true). */
    private String generatedFile(Template template, boolean touched) {
        return template.baseOutput().entrySet().stream()
            .filter(e -> !e.getKey().endsWith("/") && !e.getValue().equals("DIRECTORY"))
            .filter(e -> touched != e.getValue().equals(template.candidateOutput().get(e.getKey())))
            .map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
    }

    private TreeSet<String> expectedSnapshotPaths(Template template) {
        TreeSet<String> paths = new TreeSet<>();
        for (SourceSnapshot snapshot : List.of(template.base(), template.candidate())) {
            snapshot.manifest().files().forEach(f -> paths.add("sources/" + f.sourceId().value()));
        }

        for (Map<String, String> tree : List.of(template.baseOutput(), template.candidateOutput())) {
            tree.forEach((path, content) -> {
                if (!path.endsWith("/")) {
                    paths.add("generated/" + path);
                }
            });
        }

        return paths;
    }

    private static String hex(String text) {
        return java.util.HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private GitVersionControl realGit() throws Exception {
        Path home = Files.createDirectories(this.temp.resolve("home"));
        return new GitVersionControl("git", Map.of("HOME", home.toString(), "GIT_CONFIG_NOSYSTEM", "1"), Duration.ofSeconds(60));
    }

    private void assumeGit() throws Exception {
        assumeTrue(this.realGit().inspect(this.temp).state() != VersionControl.State.UNAVAILABLE, "git is not installed");
    }

    private String git(Workspace ws, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(ws.repository().toFile()).redirectErrorStream(true);
        builder.environment().put("HOME", this.temp.resolve("home").toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + output);
        return output;
    }
}
