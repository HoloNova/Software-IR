package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Uses the actual topic source and the existing isolated business harness, with no production test hooks. */
class CourseSelectionBusinessConformanceIT {
    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Course selection, complete prerequisites, weekly conflicts and consistent seats",
            "/topics/course/project.sir", "/conformance/mysql/course-ddl.sql", "/conformance/mysql/course-seed.sql",
            "enrollment", "/api/courses/10", "course-selection-report.txt", "course-selection", "kcg.course-conformance.enabled");

    @Test void actualCourseTopicChecksSchedulePrerequisitesAndSeatTransactions() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[COURSE-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            execute(slice);
        } catch (SQLException sql) {
            if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                slice.setNotRunReason("reference database unreachable: " + BusinessSliceHarness.describe(sql));
            } else {
                slice.check("fixture and observer SQL succeeded", false, BusinessSliceHarness.describe(sql));
                throw sql;
            }
        } finally {
            slice.teardown();
            slice.writeReport();
        }
        if (slice.notRunReason() != null) {
            System.out.println("[COURSE-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            return;
        }
        System.out.println(slice.report());
        assertTrue(slice.passed(), slice::report);
    }

    private static void execute(BusinessSliceHarness slice) throws Exception {
        var url = slice.runtimeUrl();
        slice.initializeRedaction(url);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        slice.useActorMode("external");
        var jar = slice.buildAndLocateJar(url);
        if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
        slice.setHttpStatus("READY (actual topic public course route)");
        try (var observer = slice.openObserver(url)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            CourseSelectionChecks.verify(slice, observer, new HttpAssertionClient());
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }
}
