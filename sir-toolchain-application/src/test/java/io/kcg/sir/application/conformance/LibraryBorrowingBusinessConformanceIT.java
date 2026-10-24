package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Uses the actual topic source and the existing isolated business harness, with no production test hooks. */
class LibraryBorrowingBusinessConformanceIT {
    static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Library borrowing limit, FIFO reservations and transaction consistency",
            "/valid/g9-topic-conformance.sir", "/conformance/mysql/library-ddl.sql", "/conformance/mysql/library-seed.sql",
            "loan", "/api/books/1", "library-borrowing-report.txt", "library-borrowing", "kcg.library-conformance.enabled");

    @Test void actualLibraryTopicMaintainsItsBorrowingLedgerAgainstRealMysql() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[LIBRARY-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = createSlice(environment);
        try {
            slice.verify(() -> execute(slice));
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
            System.out.println("[LIBRARY-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            return;
        }
        System.out.println(slice.report());
        assertTrue(slice.passed(), slice::report);
    }

    static BusinessSliceHarness createSlice(BusinessSliceHarness.Environment environment) {
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        slice.useActorMode("external");
        return slice;
    }

    private static void execute(BusinessSliceHarness slice) throws Exception {
        var url = slice.runtimeUrl();
        slice.initializeRedaction(url);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        var jar = slice.buildAndLocateJar(url);
        if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
        slice.setHttpStatus("READY (actual topic public book route)");
        try (var observer = slice.openObserver(url)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            var http = new HttpAssertionClient();
            G9TopicChecks.verify(slice, observer, http);
            LibraryBorrowingChecks.verify(slice, observer, http);
            LibraryReservationChecks.verify(slice, observer, http);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }
}
