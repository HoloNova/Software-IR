package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real-database business conformance for the guarded update: a credential can be rotated once, however
 * many requests ask at the same time.
 *
 * <p>This is not the ADR-017 qualification matrix and it never claims {@code QUALIFIED}; it reports
 * {@code PASSED}, {@code FAILED}, or {@code NOT_RUN} for one business slice.
 *
 * <h2>What each assertion is decisive about</h2>
 *
 * <p>Every racing request loads the credential as eligible, so none of them can be refused by looking
 * at what it read: the only thing that can turn all but one away is the guard inside the write
 * statement, evaluated by the database against the stored row. The assertions therefore count the
 * outcomes, then read the table back over an independent connection: exactly one request wins, the
 * credential is marked used once, exactly one new credential exists, and a refused request left nothing
 * behind. Two sequential refusals separate the guard (an already used credential) from the expiry check
 * (an unused but expired one), and the replay of the winning credential shows the guard still holds
 * afterwards.
 *
 * <h2>Opt-in</h2>
 *
 * <p>Without a complete reference environment the test prints {@code NOT_RUN} and returns before any
 * side effect. It is enabled with {@code -Dkcg.guard-conformance.enabled=true} and the same reference
 * environment properties as the other business slices.
 */
class GuardedTransitionBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Guarded transition (credential rotation)",
            "/valid/guarded-transition.sir",
            "/conformance/mysql/credential-rotation-ddl.sql",
            "/conformance/mysql/credential-rotation-seed.sql",
            "credential",
            "/api/get-credential?id=1",
            "guard-slice-report.txt",
            "guard-slice",
            "kcg.guard-conformance.enabled");

    private static final String GET_ROUTE = "/api/get-credential";
    private static final String REFRESH_ROUTE = "/api/refresh";

    private static final long SEEDED_ROWS = 3L;
    private static final int RACERS = 8;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void aCredentialIsRotatedOnceHoweverManyRequestsAskAtTheSameTimeAgainstRealMysql() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[GUARD-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[GUARD-CONFORMANCE] The guarded-transition business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[GUARD-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[GUARD-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[GUARD-CONFORMANCE] The guarded-transition business slice was NOT verified.");
            return;
        }

        BusinessSliceHarness slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            execute(slice);
        } catch (java.sql.SQLException unreachable) {
            // The harness's own connection to the reference database is infrastructure, not the
            // product under test: an unreachable server yields no verdict rather than a failure.
            slice.setNotRunReason("the reference database became unreachable: "
                    + BusinessSliceHarness.describe(unreachable));
        } finally {
            slice.teardown();
        }

        slice.writeReport();
        if (slice.notRunReason() != null) {
            System.out.println("[GUARD-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[GUARD-CONFORMANCE] The guarded-transition business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "guarded transition business conformance FAILED:\n" + slice.report());
    }

    /** Runs the slice end to end. Every failure is recorded; none is swallowed. */
    private void execute(BusinessSliceHarness slice) throws Exception {
        MysqlRuntimeJdbcUrl runtimeUrl = slice.runtimeUrl();
        slice.initializeRedaction(runtimeUrl);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null) {
            return;
        }

        if (slice.compileAndMaterialize().isEmpty()) {
            return;
        }

        java.nio.file.Path jar = slice.buildAndLocateJar(runtimeUrl);
        if (jar == null) {
            return;
        }

        if (!slice.startAndWaitUntilReady(jar)) {
            return;
        }

        slice.setHttpStatus("READY (200 from " + GET_ROUTE + "?id=1)");
        try (MysqlObserver observer = slice.openObserver(runtimeUrl)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            slice.check("the fixture seed is present in the owned schema",
                    slice.rowsBeforeSize() == SEEDED_ROWS, "seededRowCount=" + slice.rowsBeforeSize());
            assertGuardedBehaviour(slice, observer);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    /**
     * The business assertions. Each one is a fresh independent HTTP request, cross-checked against rows
     * read by an independent connection.
     */
    private void assertGuardedBehaviour(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        // G1: an already used credential is refused by the guard, and nothing is written.
        HttpAssertionClient.Response used = http.postJson(base + REFRESH_ROUTE, Map.of(), "{\"id\":2}");
        slice.record("G1 POST refresh (already used)", used);
        slice.expectStatus("an already used credential is refused with the declared 409", used, 409);
        slice.check("the refusal wrote nothing: still three rows and row 2 unchanged",
                slice.rowsNow(observer).size() == SEEDED_ROWS && isUsed(rowOf(slice, observer, 2)),
                "rows=" + slice.rowsNow(observer));

        // G2: an expired credential is refused by the expiry check, and nothing is written.
        HttpAssertionClient.Response expired = http.postJson(base + REFRESH_ROUTE, Map.of(), "{\"id\":3}");
        slice.record("G2 POST refresh (expired)", expired);
        slice.expectStatus("an expired credential is refused with the declared 400", expired, 400);
        slice.check("the refusal wrote nothing: still three rows and row 3 still unused",
                slice.rowsNow(observer).size() == SEEDED_ROWS && !isUsed(rowOf(slice, observer, 3)),
                "rows=" + slice.rowsNow(observer));

        // G3: an unknown credential is the declared 404.
        HttpAssertionClient.Response missing = http.postJson(base + REFRESH_ROUTE, Map.of(), "{\"id\":999999}");
        slice.record("G3 POST refresh (unknown)", missing);
        slice.expectStatus("an unknown credential is refused with the declared 404", missing, 404);

        // G4: many requests rotate the same eligible credential at once.
        List<HttpAssertionClient.Response> raced = raceRefresh(http, base, 1, RACERS);
        for (int index = 0; index < raced.size(); index++) {
            slice.record("G4 POST refresh (racer " + index + ")", raced.get(index));
        }

        List<Integer> statuses = new ArrayList<>();
        raced.forEach(response -> statuses.add(response.statusCode()));
        statuses.sort(Integer::compareTo);
        long winners = statuses.stream().filter(status -> status >= 200 && status < 300).count();
        long refused = statuses.stream().filter(status -> status == 409).count();
        slice.check("exactly one of " + RACERS + " simultaneous requests rotates the credential",
                winners == 1, "statuses=" + statuses);
        slice.check("every other simultaneous request is refused with the declared 409",
                refused == RACERS - 1, "statuses=" + statuses);

        List<String> rows = slice.rowsNow(observer);
        slice.check("the rotation created exactly one new credential, so the refused requests left none",
                rows.size() == SEEDED_ROWS + 1, "rows=" + rows);
        slice.check("the rotated credential is marked used exactly once",
                isUsed(rowOf(slice, observer, 1)), "row=" + rowOf(slice, observer, 1));

        HttpAssertionClient.Response winner = raced.stream()
                .filter(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                .findFirst()
                .orElse(null);
        if (winner != null) {
            String newId = BusinessSliceHarness.field(winner.body(), "id");
            slice.expectFields("the winner answers with the new credential, owned by the same owner and unused",
                    winner, Map.of("owner", "alice", "used", "false"));
            slice.check("the new credential is stored unused",
                    !newId.isEmpty() && !isUsed(rowOf(slice, observer, Long.parseLong(newId))),
                    "newId=" + newId + " rows=" + rows);
            String expiresText = BusinessSliceHarness.field(winner.body(), "expiresAt");
            try {
                Instant expiresAt = Instant.parse(expiresText);
                Instant now = Instant.now();
                slice.check("the new credential expires about seven days from now (time shift evaluated once)",
                        expiresAt.isAfter(now.plusSeconds(6L * 24 * 3600)) && expiresAt.isBefore(now.plusSeconds(8L * 24 * 3600)),
                        "expiresAt=" + expiresAt);
            } catch (java.time.format.DateTimeParseException notAnInstant) {
                slice.check("the new credential's expiry is an ISO instant", false, "expiresAt=" + expiresText);
            }
        }

        // G5: replaying the credential that was just rotated is refused by the guard, and nothing is written.
        HttpAssertionClient.Response replay = http.postJson(base + REFRESH_ROUTE, Map.of(), "{\"id\":1}");
        slice.record("G5 POST refresh (replay of the rotated credential)", replay);
        slice.expectStatus("replaying the rotated credential is refused with the declared 409", replay, 409);
        slice.check("the replay wrote nothing: still four rows",
                slice.rowsNow(observer).size() == SEEDED_ROWS + 1, "rows=" + slice.rowsNow(observer));

        // G6: the untouched credentials are exactly as seeded, so the guarded statement wrote only its own row.
        slice.check("the other seeded credentials are unchanged",
                rowOf(slice, observer, 2).startsWith("2|bob|") && isUsed(rowOf(slice, observer, 2))
                        && rowOf(slice, observer, 3).startsWith("3|carol|") && !isUsed(rowOf(slice, observer, 3)),
                "rows=" + slice.rowsNow(observer));
    }

    /**
     * Sends the same refresh from several threads released together by a barrier, so none can finish
     * before the others have started; the generated write is a single guarded statement, so the database
     * decides the winner.
     */
    private static List<HttpAssertionClient.Response> raceRefresh(
            HttpAssertionClient http, String base, long id, int racers) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(racers);
        ExecutorService executor = Executors.newFixedThreadPool(racers);
        try {
            List<Callable<HttpAssertionClient.Response>> tasks = new ArrayList<>();
            for (int index = 0; index < racers; index++) {
                tasks.add(() -> {
                    barrier.await();
                    return http.postJson(base + REFRESH_ROUTE, Map.of(), "{\"id\":" + id + "}");
                });
            }

            List<HttpAssertionClient.Response> responses = new ArrayList<>();
            for (Future<HttpAssertionClient.Response> result : executor.invokeAll(tasks)) {
                responses.add(result.get());
            }

            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String rowOf(BusinessSliceHarness slice, MysqlObserver observer, long id) throws java.sql.SQLException {
        return slice.rowsNow(observer).stream().filter(row -> row.startsWith(id + "|")).findFirst().orElse("<absent>");
    }

    /** The third column is the used flag; the driver renders a TINYINT(1) as 1 or true. */
    private static boolean isUsed(String row) {
        String[] columns = row.split("\\|");
        return columns.length > 2 && ("1".equals(columns[2]) || "true".equalsIgnoreCase(columns[2]));
    }
}
