package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** One cropped reference or external candidate, one generated application, the same six-capability events oracle. */
class EventsRegistrationBusinessConformanceIT {
    static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Event registration, seat counters, unique attendees and fee sums", "/valid/events-registration.sir",
            "/conformance/mysql/events-registration-ddl.sql", "/conformance/mysql/events-registration-seed.sql", "registration",
            "/api/events?page=1&size=10", "events-slice-report.txt", "events-slice", "kcg.events-conformance.enabled");

    static BusinessSliceHarness createSlice(BusinessSliceHarness.Environment environment) {
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        slice.useActorMode("external");
        return slice;
    }

    @Test
    void registrationsSeatsFeesAndContendedWritesMatchHttpAndIndependentDatabaseState() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[EVENTS-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
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
        if (slice.notRunReason() == null) assertTrue(slice.passed(), () -> "events business conformance FAILED:\n" + slice.report());
    }

    private static void execute(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment) throws Exception {
        var runtime = slice.runtimeUrl();
        slice.initializeRedaction(runtime);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        try (var fixture = new EventsFixture(slice, environment)) {
            var initial = fixture.snapshot();
            slice.setRowsBefore(initial.table("registration").stream().map(Object::toString).toList());
            slice.reportLine("eventsInitialDatabase=" + initial);
            if (!fixture.observabilityAvailable()) return;
            var jar = slice.buildAndLocateJar(runtime);
            if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
            slice.setHttpStatus("READY (200 from " + FIXTURE.readinessPath() + ")");
            var a = new EventsAssertions(slice, fixture);
            a.unchanged("B01 startup readiness GET", initial, fixture.snapshot());
            if (!section(a, "identity and creation", () -> EventsChecks.identityAndCreation(a))) return;
            if (!section(a, "listing and own registrations", () -> EventsChecks.listing(a))) return;
            if (!section(a, "revenue and headcount", () -> EventsChecks.revenue(a))) return;
            if (!section(a, "registering", () -> EventsChecks.registration(a))) return;
            if (!section(a, "capacity race", () -> EventsChecks.capacityRace(a))) return;
            if (!section(a, "same attendee race", () -> EventsChecks.sameUserRace(a))) return;
            var after = fixture.snapshot();
            slice.setRowsAfter(after.table("registration").stream().map(Object::toString).toList());
            slice.reportLine("eventsFinalDatabase=" + after);
        }
    }

    private static boolean section(EventsAssertions a, String label, BusinessSliceHarness.Body body) throws Exception {
        if (!a.fixture.reset()) return false;
        a.slice.reportLine("eventsSection=" + label);
        body.run();
        return true;
    }
}
