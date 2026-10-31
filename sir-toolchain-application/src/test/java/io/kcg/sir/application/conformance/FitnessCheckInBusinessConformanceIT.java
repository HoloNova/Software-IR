package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** One cropped reference or external candidate, one generated application, the same seven-capability gym oracle. */
class FitnessCheckInBusinessConformanceIT {
    static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Gym check-ins, Asia/Shanghai monthly statistics and coach views", "/valid/fitness-check-in.sir",
            "/conformance/mysql/fitness-check-in-ddl.sql", "/conformance/mysql/fitness-check-in-seed.sql", "check_in",
            "/api/programs?page=1&size=10", "fitness-slice-report.txt", "fitness-slice", "kcg.fitness-conformance.enabled");

    static BusinessSliceHarness createSlice(BusinessSliceHarness.Environment environment) {
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        slice.useActorMode("external");
        return slice;
    }

    @Test
    void checkInsMonthlyStatisticsAndCoachViewsMatchHttpAndIndependentDatabaseState() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[FITNESS-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = createSlice(environment);
        try {
            slice.verify(() -> execute(slice, environment));
        } catch (SQLException error) {
            if (error.getSQLState() == null || !error.getSQLState().startsWith("08")) throw error;
            slice.setNotRunReason("the reference database became unreachable: " + BusinessSliceHarness.describe(error));
        } finally {
            try { slice.teardown(); } finally { slice.writeReport(); }
        }
        System.out.println(slice.report());
        if (slice.notRunReason() == null) assertTrue(slice.passed(), () -> "fitness business conformance FAILED:\n" + slice.report());
    }

    private static void execute(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment) throws Exception {
        var runtime = slice.runtimeUrl();
        slice.initializeRedaction(runtime);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        try (var fixture = new FitnessFixture(slice, environment)) {
            var initial = fixture.snapshot();
            slice.setRowsBefore(initial.table("check_in").stream().map(Object::toString).toList());
            slice.reportLine("fitnessInitialDatabase=" + initial);
            var jar = slice.buildAndLocateJar(runtime);
            if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
            slice.setHttpStatus("READY (200 from " + FIXTURE.readinessPath() + ")");
            var a = new FitnessAssertions(slice, fixture);
            a.unchanged("B01 startup readiness GET", initial, fixture.snapshot());
            if (!section(a, "identity and programs", () -> FitnessCheckInChecks.identityAndPrograms(a))) return;
            if (!section(a, "logging workouts", () -> FitnessCheckInChecks.workouts(a))) return;
            if (!section(a, "monthly statistics", () -> FitnessCheckInChecks.statistics(a))) return;
            if (!section(a, "statistics after writes", () -> FitnessCheckInChecks.statisticsAfterWrites(a))) return;
            if (!section(a, "coach views", () -> FitnessCheckInChecks.coach(a))) return;
            if (!section(a, "coach sees new data", () -> FitnessCheckInChecks.coachSeesNewData(a))) return;
            if (!section(a, "independent recomputation", () -> FitnessCheckInChecks.recomputation(a))) return;
            var after = fixture.snapshot();
            slice.setRowsAfter(after.table("check_in").stream().map(Object::toString).toList());
            slice.reportLine("fitnessFinalDatabase=" + after);
        }
    }

    private static boolean section(FitnessAssertions a, String label, BusinessSliceHarness.Body body) throws Exception {
        if (!a.fixture.reset()) return false;
        a.slice.reportLine("fitnessSection=" + label);
        body.run();
        return true;
    }
}
