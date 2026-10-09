package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Q21: the actual multi-source delivered tree, not a regenerated substitute, is built and served. */
class ProjectWorkflowBusinessConformanceIT {
    private static final SourceId ENTRY = SourceId.of("project.sir");
    private static final String RESOURCE = "/valid/multi-course/";
    private static final String ENABLED = "kcg.project-change-conformance.enabled";
    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Q21 multi-source workflow loop", RESOURCE + "project.sir",
            "/conformance/mysql/change-loop-ddl.sql", "/conformance/mysql/change-loop-seed.sql",
            "course", "/api/search-course-enrollments?page=1&size=10",
            "project-workflow-report.txt", "project-workflow", ENABLED);

    @Test void multiSourceUpdatesReachTheSameRunningProjectWithoutChangingAnyTable() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, ENABLED);
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[PROJECT-WORKFLOW-CONFORMANCE] NOT_RUN: disabled/incomplete reference environment "
                    + environment.missingPrerequisites());
            return;
        }
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            execute(slice, environment);
        } catch (java.sql.SQLException unreachable) {
            slice.setNotRunReason("reference database unreachable: " + BusinessSliceHarness.describe(unreachable));
        } catch (Exception | AssertionError failure) {
            slice.setFailure("multi-source workflow loop: " + BusinessSliceHarness.describe(failure));
        } finally {
            slice.teardown();
        }
        slice.writeReport();
        if (slice.notRunReason() != null) {
            System.out.println("[PROJECT-WORKFLOW-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            return;
        }
        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "Q21 business conformance FAILED:\n" + slice.report());
    }

    private void execute(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment) throws Exception {
        var runtime = slice.runtimeUrl();
        slice.initializeRedaction(runtime);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null) return;
        Path sources = slice.workPath("multi-sources"), state = slice.workPath("project-state");
        for (String name : List.of("project.sir", "modules/course.sir", "modules/student.sir", "modules/enrollment.sir")) {
            Path path = sources.resolve(name);
            Files.createDirectories(path.getParent());
            try (var stream = getClass().getResourceAsStream(RESOURCE + name)) {
                if (stream == null) throw new IllegalStateException("missing multi-source fixture " + name);
                Files.write(path, stream.readAllBytes());
            }
        }
        var generated = assertInstanceOf(ProjectToolchainResult.Success.class,
                new ToolchainApplication().executeProject(new ProjectToolchainRequest(sources, ENTRY, slice.projectRoot())));
        var compiled = ProjectBaselineVerification.compile(generated.sources(), slice.projectRoot(), new ArrayList<>());
        assertNotNull(compiled);
        slice.recordMaterializedProject(generated.sources(), compiled.compilation().generatedFiles());
        var baseline = assertInstanceOf(ProjectBaselineResult.Success.class, new ProjectBaselineApplication().register(
                new ProjectBaselineRegistrationRequest(generated.sources(), slice.projectRoot(), state)));
        String b0 = baseline.receipt().baselineId();
        Files.move(sources, sources.resolveSibling("original-sources-unavailable"));
        slice.reportLine("sourceCount=" + baseline.sources().manifest().files().size());
        slice.reportLine("originalSourceDirectoryAvailable=false");
        slice.reportLine("projectWorkflowFileUpdateImplemented=true databaseEvolutionImplemented=false");
        slice.reportLine("B0=" + b0);
        Path jar = slice.buildAndLocateJar(runtime);
        if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
        slice.setHttpStatus("READY (200 from " + FIXTURE.readinessPath() + ")");
        try (var observer = slice.openObserver(runtime)) {
            var initial = fingerprint(observer, environment);
            slice.setRowsBefore(slice.rowsNow(observer));
            assertCourses(slice, "B0", List.of("CS101", "CS102", "MAT101"));
            SourceSnapshot widened = replace(baseline.sources(), "status == EnrollmentStatus.ACTIVE",
                    "(status == EnrollmentStatus.ACTIVE or status == EnrollmentStatus.CANCELLED)");
            String b1 = apply(slice, state, b0, widened, "R1");
            if (!slice.rebuildAndRestart(runtime)) return;
            assertCourses(slice, "B1", List.of("ART101", "CS101", "CS102", "MAT101"));
            slice.check("R1 leaves all three database tables byte-for-byte unchanged", initial.equals(fingerprint(observer, environment)), "course/student/enrollment row fingerprints");
            // A second fresh process/context uses the saved B1 and proves history is not a one-shot overwrite.
            String restored = apply(slice, state, b1, baseline.sources(), "R2");
            slice.check("R2 can return to the original immutable baseline ID", b0.equals(restored), "CURRENT=" + restored);
            if (!slice.rebuildAndRestart(runtime)) return;
            assertCourses(slice, "B2 (original filter restored)", List.of("CS101", "CS102", "MAT101"));
            var after = fingerprint(observer, environment);
            slice.check("both updates preserve all table rows and values", initial.equals(after), "before=" + initial + " after=" + after);
            slice.setRowsAfter(slice.rowsNow(observer));
            slice.reportLine("databaseRowFingerprint=" + after);
        }
    }

    private static String apply(BusinessSliceHarness slice, Path state, String baseline, SourceSnapshot candidate, String round) throws Exception {
        var context = assertInstanceOf(ProjectChangeContextResult.Success.class, new ProjectChangePlanningApplication().context(
                new ProjectChangeContextRequest(state, slice.projectRoot(), baseline, candidate))).context();
        var target = context.targets().stream().filter(t -> t.displayName().equals("SearchCourseEnrollments")).findFirst().orElseThrow();
        var request = new ProjectChangePlanningRequest(context, candidate, context.targetKey(target));
        var planned = assertInstanceOf(ProjectChangePlanningResult.Planned.class, new ProjectChangePlanningApplication().plan(request));
        slice.check(round + " is exactly the existing query Service UPDATE", planned.plan().fileChanges().size() == 1
                && planned.plan().fileChanges().getFirst().relativePath().endsWith("/SearchCourseEnrollmentsService.java"), planned.plan().fileChanges().toString());
        var result = assertInstanceOf(ProjectChangeApplyResult.Applied.class, new ProjectChangeExecutionApplication().apply(new ProjectChangeApplyRequest(request, planned)));
        var saved = assertInstanceOf(ProjectBaselineResult.Success.class, new ProjectBaselineApplication().inspect(
                new ProjectBaselineInspectionRequest(state, slice.projectRoot(), result.candidate().baselineId())));
        slice.check(round + " reopens every candidate source byte without its source directory", saved.sources().manifest().equals(candidate.manifest())
                && candidate.manifest().files().stream().allMatch(f -> Arrays.equals(candidate.bytes(f.sourceId()), saved.sources().bytes(f.sourceId()))), saved.receipt().toString());
        var replay = ProjectBaselineVerification.compile(candidate, slice.projectRoot(), new ArrayList<>());
        assertNotNull(replay);
        for (var file : replay.compilation().generatedFiles()) assertArrayEquals(file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(slice.projectRoot().resolve(file.relativePath())), file.relativePath());
        slice.check(round + " delivered managed files equal the full candidate generation", true, "files=" + replay.compilation().generatedFiles().size());
        slice.reportLine(round + " baseline=" + result.candidate().baselineId() + " sourceSetSha256=" + candidate.sha256Hex() + " graphCanonicalDigest=" + saved.receipt().graphCanonicalDigest());
        return result.candidate().baselineId();
    }

    private static SourceSnapshot replace(SourceSnapshot base, String old, String next) {
        var bytes = new LinkedHashMap<SourceId, byte[]>();
        base.manifest().files().forEach(f -> bytes.put(f.sourceId(), base.bytes(f.sourceId())));
        String text = new String(bytes.get(ENTRY), StandardCharsets.UTF_8);
        assertTrue(text.contains(old), "fixture condition is reachable");
        bytes.put(ENTRY, text.replace(old, next).getBytes(StandardCharsets.UTF_8));
        return new SourceSnapshot(ENTRY, bytes);
    }
    private static Map<String, List<String>> fingerprint(MysqlObserver observer, BusinessSliceHarness.Environment environment) throws Exception {
        var rows = new TreeMap<String, List<String>>();
        for (String table : List.of("course", "student", "enrollment")) rows.put(table, observer.queryRows(environment.schemaName(), table, "id"));
        return rows;
    }
    private static void assertCourses(BusinessSliceHarness slice, String label, List<String> expected) throws Exception {
        var response = new HttpAssertionClient().get(slice.baseUrl() + FIXTURE.readinessPath(), Map.of());
        slice.record(label + " GET course search", response);
        if (!slice.expectStatus(label + " serves the updated query", response, 200)) return;
        var codes = new ArrayList<String>();
        var matches = Pattern.compile("\"code\"\\s*:\\s*\"((?:ART|CS|MAT|PHY)[0-9]+)\"").matcher(response.body());
        while (matches.find()) codes.add(matches.group(1));
        slice.check(label + " exact ordered course codes", codes.equals(expected), "codes=" + codes);
        slice.expectField(label + " total counts the same roots", response, "total", Integer.toString(expected.size()));
        slice.check(label + " ART101 appears only for the widened filter", response.body().contains("ART101") == expected.contains("ART101"), "body=" + response.body());
    }
}
