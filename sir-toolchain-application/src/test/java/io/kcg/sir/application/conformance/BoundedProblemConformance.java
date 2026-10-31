package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;

/** One generated application per problem, one oracle for either the reference or its frozen candidate. */
final class BoundedProblemConformance {
    @FunctionalInterface interface Checks { void run(BoundedProblemHttp assertions) throws Exception; }
    record Section(String label, Checks checks) { }
    private BoundedProblemConformance() { }

    static BusinessSliceHarness createSlice(BusinessSliceHarness.Fixture fixture, BusinessSliceHarness.Environment environment) {
        var slice = new BusinessSliceHarness(fixture, environment);
        slice.useActorMode("external");
        return slice;
    }

    static void verify(BusinessSliceHarness.Fixture fixture, BoundedProblemFixture.Definition definition,
                       String tokenPrefix, List<Section> sections) throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, fixture.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[" + fixture.runTokenPrefix() + "] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = createSlice(fixture, environment);
        try { slice.verify(() -> execute(slice, environment, fixture, definition, tokenPrefix, sections)); }
        catch (SQLException error) {
            if (error.getSQLState() == null || !error.getSQLState().startsWith("08")) throw error;
            slice.setNotRunReason("database unreachable: " + BusinessSliceHarness.describe(error));
        } finally {
            try { slice.teardown(); } finally { slice.writeReport(); }
        }
        System.out.println(slice.report());
        if (slice.notRunReason() == null) assertTrue(slice.passed(), slice::report);
    }

    private static void execute(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment,
                                BusinessSliceHarness.Fixture fixture, BoundedProblemFixture.Definition definition,
                                String tokenPrefix, List<Section> sections) throws Exception {
        var runtime = slice.runtimeUrl();
        slice.initializeRedaction(runtime);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        try (var database = new BoundedProblemFixture(slice, environment, fixture, definition)) {
            if (!database.reset() || !database.observabilityAvailable()) return;
            var initial = database.snapshot();
            slice.setRowsBefore(initial.table(fixture.table()).stream().map(Object::toString).toList());
            var jar = slice.buildAndLocateJar(runtime);
            if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
            slice.setHttpStatus("READY (200 from " + fixture.readinessPath() + ")");
            var assertions = new BoundedProblemHttp(slice, database, tokenPrefix);
            assertions.expected("startup readiness unchanged", initial, database.snapshot());
            for (var section : sections) {
                if (!database.reset()) return;
                slice.reportLine("section=" + section.label());
                section.checks().run(assertions);
            }
            var after = database.snapshot();
            slice.setRowsAfter(after.table(fixture.table()).stream().map(Object::toString).toList());
            slice.reportLine("finalDatabase=" + after);
        }
    }
}
