package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ProjectBaselineApplication;
import io.kcg.sir.application.api.ProjectBaselineRegistrationRequest;
import io.kcg.sir.application.api.ProjectBaselineResult;
import io.kcg.sir.application.api.ProjectToolchainRequest;
import io.kcg.sir.application.api.ProjectToolchainResult;
import io.kcg.sir.application.api.SourceDelta;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.api.VersionPlan.Action;
import io.kcg.sir.application.api.VersionPlanningApplication;
import io.kcg.sir.application.api.VersionPlanningRequest;
import io.kcg.sir.application.api.VersionPlanningResult;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A version plan compares the stored project with a candidate. The oracle is the production
 * toolchain: it generates the candidate from scratch into its own directory, and the plan must say
 * exactly what turns the stored output into that directory.
 */
class VersionPlanningTest {
    private static final VersionPlanningApplication PLANNER = new VersionPlanningApplication();
    private static final String NEW_CAPABILITY = """
            capability FindCourses {
              input SearchCourseEnrollmentsInput;
              output Page<CourseEnrollmentItem>;
              fails InvalidPage;
              requires readonly;
              expose query;
              workflow {
                find Course
                  where any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE)
                  order by code ascending
                  Page input.page, input.size else InvalidPage
                as courses;
                return courses;
              }
            }
        """;

    @TempDir Path temp;

    private record Stored(Path output, Path state, SourceSnapshot sources, String baselineId) {}

    private Stored stored() throws Exception {
        Path source = MultiSourceTestSupport.fixture(this.temp);
        Path output = this.temp.resolve("output");
        ProjectToolchainResult.Success generated = MultiSourceTestSupport.generate(source, output);
        Path state = this.temp.resolve("state");
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class,
            new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(generated.sources(), output, state)));
        return new Stored(output, state, generated.sources(), saved.receipt().baselineId());
    }

    private static String text(SourceSnapshot snapshot, String file) {
        return new String(snapshot.bytes(SourceId.of(file)), StandardCharsets.UTF_8);
    }

    /** The snapshot with edits applied: {file, from, to}; {file, null, content} adds a file; {file, content, null} removes one. */
    private static SourceSnapshot edit(SourceSnapshot base, String[]... edits) {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (var file : base.manifest().files()) {
            inputs.put(file.sourceId(), base.bytes(file.sourceId()));
        }

        for (String[] edit : edits) {
            SourceId id = SourceId.of(edit[0]);
            if (edit[1] == null) {
                inputs.put(id, edit[2].getBytes(StandardCharsets.UTF_8));
            } else if (edit[2] == null) {
                inputs.remove(id);
            } else {
                String text = new String(inputs.get(id), StandardCharsets.UTF_8);
                assertTrue(text.contains(edit[1]), "fixture changed: " + edit[1]);
                inputs.put(id, text.replace(edit[1], edit[2]).getBytes(StandardCharsets.UTF_8));
            }
        }

        return new SourceSnapshot(base.entry(), inputs);
    }

    private VersionPlanningRequest request(Stored stored, SourceSnapshot candidate) {
        return new VersionPlanningRequest(stored.state(), stored.output(), stored.baselineId(), candidate);
    }

    private static VersionPlan planned(VersionPlanningResult result) {
        return assertInstanceOf(VersionPlanningResult.Planned.class, result, result.diagnostics().toString()).plan();
    }

    /** Generates {@code candidate} from scratch with the production project toolchain. */
    private Map<String, String> fromScratch(SourceSnapshot candidate, String name) throws Exception {
        Path sources = this.temp.resolve(name + "-sources");
        for (var file : candidate.manifest().files()) {
            Path path = sources.resolve(file.sourceId().value());
            Files.createDirectories(path.getParent());
            Files.write(path, candidate.bytes(file.sourceId()));
        }

        Path output = this.temp.resolve(name + "-output");
        var result = new ToolchainApplication().executeProject(new ProjectToolchainRequest(sources, candidate.entry(), output));
        assertInstanceOf(ProjectToolchainResult.Success.class, result, result.diagnostics().toString());
        return MultiSourceTestSupport.files(output);
    }

    private static Map<String, Action> expected(Map<String, String> before, Map<String, String> after) {
        Map<String, Action> out = new TreeMap<>();
        before.forEach((path, hex) -> {
            if (!after.containsKey(path)) {
                out.put(path, Action.DELETE);
            } else if (!after.get(path).equals(hex)) {
                out.put(path, Action.UPDATE);
            }
        });
        after.keySet().stream().filter(path -> !before.containsKey(path)).forEach(path -> out.put(path, Action.CREATE));
        return out;
    }

    private static Map<String, Action> actions(VersionPlan plan) {
        Map<String, Action> out = new TreeMap<>();
        plan.files().forEach(f -> out.put(f.path(), f.action()));
        return out;
    }

    private SourceSnapshot mixedCandidate(Stored stored) {
        String getCourse = text(stored.sources(), "project.sir");
        int from = getCourse.indexOf("    capability GetCourse {");
        int to = getCourse.indexOf("    capability CreateCourse {");
        assertTrue(from > 0 && to > from, "fixture changed");
        String removed = getCourse.substring(from, to);
        return edit(stored.sources(),
            new String[] {"project.sir", removed, NEW_CAPABILITY},
            new String[] {"project.sir", "field code: String where notBlank, length(1, 32);", "field code: String where notBlank, length(1, 40);"});
    }

    @Test
    void oneVersionChangeCanCreateUpdateAndDeleteGeneratedFilesTogether() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot candidate = this.mixedCandidate(stored);

        VersionPlan plan = planned(PLANNER.plan(this.request(stored, candidate)));

        assertEquals(expected(MultiSourceTestSupport.files(stored.output()), this.fromScratch(candidate, "candidate")), actions(plan));
        assertTrue(plan.count(Action.CREATE) > 0 && plan.count(Action.UPDATE) > 0 && plan.count(Action.DELETE) > 0, plan.files().toString());
        assertFalse(plan.sourceOnly());
        assertEquals(stored.baselineId(), plan.base().baselineId());
        assertNotEquals(plan.base().baselineId(), plan.candidate().baselineId());
        assertEquals(List.of("project.sir"), plan.sources().entries().stream().filter(e -> e.action() == SourceDelta.Action.CHANGED).map(e -> e.source().value()).toList());
        assertEquals(plan.files().size() + plan.unchangedFiles(), MultiSourceTestSupport.files(this.temp.resolve("candidate-output")).size() + plan.count(Action.DELETE));
    }

    @Test
    void thePlanIsReadOnlyAndRepeatable() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot candidate = this.mixedCandidate(stored);
        Map<String, String> state = MultiSourceTestSupport.tree(stored.state());
        Map<String, String> output = MultiSourceTestSupport.tree(stored.output());

        VersionPlan first = planned(PLANNER.plan(this.request(stored, candidate)));
        VersionPlan second = planned(PLANNER.plan(this.request(stored, candidate)));

        assertEquals(first, second);
        assertEquals(first.sha256Hex(), second.sha256Hex());
        assertEquals(state, MultiSourceTestSupport.tree(stored.state()), "planning does not touch the state directory");
        assertEquals(output, MultiSourceTestSupport.tree(stored.output()), "planning does not touch the generated project");
    }

    @Test
    void aCandidateThatAddsASourceFileCreatesItsGeneratedFiles() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot candidate = edit(stored.sources(),
            new String[] {"modules/teacher.sir", null, "sir 0.2\nimports {}\ndeclarations {\n  entity Teacher persistent {\n    identity id: Int64 generated auto;\n    field name: String where notBlank, length(1, 100);\n  }\n}\n"},
            new String[] {"project.sir", "source \"modules/enrollment.sir\";", "source \"modules/enrollment.sir\";\n    source \"modules/teacher.sir\";"},
            new String[] {"project.sir", "import Student from \"modules/student.sir\";", "import Student from \"modules/student.sir\";\n    import Teacher from \"modules/teacher.sir\";"});

        VersionPlan plan = planned(PLANNER.plan(this.request(stored, candidate)));

        assertEquals(expected(MultiSourceTestSupport.files(stored.output()), this.fromScratch(candidate, "added")), actions(plan));
        assertTrue(plan.files().stream().anyMatch(f -> f.action() == Action.CREATE && f.path().endsWith("/domain/Teacher.java")), plan.files().toString());
        assertEquals(1, plan.sources().entries().stream().filter(e -> e.action() == SourceDelta.Action.ADDED).count());
    }

    @Test
    void aCandidateThatOnlyChangesTheSourceIsAValidPlanWithNoFileOperation() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot comment = edit(stored.sources(), new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// reviewed"});

        VersionPlan plan = planned(PLANNER.plan(this.request(stored, comment)));

        assertTrue(plan.sourceOnly());
        assertEquals(0, plan.files().size());
        assertTrue(plan.unchangedFiles() > 0);
        assertNotEquals(plan.base().baselineId(), plan.candidate().baselineId(), "a different source is a different version");
    }

    @Test
    void movingADeclarationBetweenSourceFilesIsSourceOnly() throws Exception {
        Stored stored = this.stored();
        String inProject = "    view StudentSummary from Student {\n      field id: Int64;\n      field studentNo: String;\n      field name: String;\n    }\n";
        String inStudent = "  view StudentSummary from Student {\n    field id: Int64;\n    field studentNo: String;\n    field name: String;\n  }\n";
        SourceSnapshot candidate = edit(stored.sources(),
            new String[] {"project.sir", inProject, ""},
            new String[] {"project.sir", "import Student from \"modules/student.sir\";", "import Student from \"modules/student.sir\";\n    import StudentSummary from \"modules/student.sir\";"},
            new String[] {"modules/student.sir", "  }\n}", "  }\n" + inStudent + "}"});

        VersionPlan plan = planned(PLANNER.plan(this.request(stored, candidate)));

        assertTrue(plan.sourceOnly());
        assertEquals(2, plan.sources().entries().stream().filter(e -> e.action() == SourceDelta.Action.CHANGED).count());
    }

    @Test
    void theStoredVersionItselfIsNoChange() throws Exception {
        Stored stored = this.stored();

        var result = assertInstanceOf(VersionPlanningResult.NoChanges.class, PLANNER.plan(this.request(stored, stored.sources())));

        assertEquals(stored.baselineId(), result.baseline().baselineId());
    }

    @Test
    void verifyAcceptsTheSamePlanAndRejectsAPlanForAnotherCandidate() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot candidate = this.mixedCandidate(stored);
        var planned = assertInstanceOf(VersionPlanningResult.Planned.class, PLANNER.plan(this.request(stored, candidate)));
        SourceSnapshot other = edit(stored.sources(), new String[] {"modules/student.sir", "sir 0.2", "sir 0.2\n// other"});

        assertInstanceOf(VersionPlanningResult.Planned.class, PLANNER.verify(this.request(stored, candidate), planned));
        assertInstanceOf(VersionPlanningResult.Failure.class, PLANNER.verify(this.request(stored, other), planned));
    }

    @Test
    void aStaleBaselineAHandEditedFileOrAnOccupiedPathIsRefusedBeforeAnyPlan() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot candidate = this.mixedCandidate(stored);
        VersionPlan plan = planned(PLANNER.plan(this.request(stored, candidate)));

        var wrongBaseline = new VersionPlanningRequest(stored.state(), stored.output(), "0".repeat(64), candidate);
        assertInstanceOf(VersionPlanningResult.Failure.class, PLANNER.plan(wrongBaseline));

        VersionPlan.FileOp created = plan.files().stream().filter(f -> f.action() == Action.CREATE).findFirst().orElseThrow();
        Files.createDirectories(stored.output().resolve(created.path()).getParent());
        Files.writeString(stored.output().resolve(created.path()), "somebody else's file\n");
        var occupied = assertInstanceOf(VersionPlanningResult.Failure.class, PLANNER.plan(this.request(stored, candidate)));
        assertTrue(occupied.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-APP-VERSION-CONFLICT-001") && d.message().contains(created.path())), occupied.diagnostics().toString());
        Files.delete(stored.output().resolve(created.path()));

        VersionPlan.FileOp updated = plan.files().stream().filter(f -> f.action() == Action.UPDATE).findFirst().orElseThrow();
        Files.writeString(stored.output().resolve(updated.path()), "edited by hand\n");
        var edited = assertInstanceOf(VersionPlanningResult.Failure.class, PLANNER.plan(this.request(stored, candidate)));
        assertTrue(edited.diagnostics().stream().anyMatch(ExecutionDiagnostic::isError));
    }

    @Test
    void aBrokenCandidateIsReportedAtItsStage() throws Exception {
        Stored stored = this.stored();
        SourceSnapshot broken = edit(stored.sources(), new String[] {"modules/enrollment.sir", "Ref<Course>", "Ref<Missing>"});

        var failure = assertInstanceOf(VersionPlanningResult.Failure.class, PLANNER.plan(this.request(stored, broken)));

        assertEquals(io.kcg.sir.application.api.ExecutionStage.SEMANTIC, failure.failedStage());
    }

    @Test
    void thePlanRecordRefusesInconsistentEvidence() {
        String sha = "a".repeat(64);
        String other = "b".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new VersionPlan.FileOp("a/B.java", Action.CREATE, Optional.empty(), 3, sha, 4, other), "a CREATE has no before");
        assertThrows(IllegalArgumentException.class, () -> new VersionPlan.FileOp("a/B.java", Action.UPDATE, Optional.empty(), 3, sha, 4, sha), "an UPDATE must change the bytes");
        assertThrows(IllegalArgumentException.class, () -> new VersionPlan.FileOp("../B.java", Action.DELETE, Optional.empty(), 3, sha, 0, ""), "unsafe path");
        new VersionPlan.FileOp("a/B.java", Action.UPDATE, Optional.empty(), 3, sha, 4, other);

        List<VersionPlan.FileOp> tooMany = new ArrayList<>();
        for (int i = 0; i <= VersionPlan.MAX_FILE_OPS; i++) {
            tooMany.add(new VersionPlan.FileOp(String.format("f/%04d.java", i), Action.DELETE, Optional.empty(), 1, sha, 0, ""));
        }

        var root = this.temp.toAbsolutePath().normalize();
        var one = new io.kcg.sir.application.api.ProjectBaselineReceipt(sha, root, SourceId.of("p.sir"), sha, sha, sha);
        var two = new io.kcg.sir.application.api.ProjectBaselineReceipt(other, root, SourceId.of("p.sir"), sha, sha, sha);
        var sources = new SourceDelta(List.of());
        assertThrows(IllegalArgumentException.class, () -> new VersionPlan(root, one, two, sources, tooMany, 0));
        assertThrows(IllegalArgumentException.class, () -> new VersionPlan(root, one, one, sources, List.of(), 0), "a plan joins two different versions");
        assertThrows(IllegalArgumentException.class, () -> new VersionPlan(root, one, two, sources, List.of(tooMany.get(1), tooMany.get(0)), 0), "operations are sorted");
    }
}
