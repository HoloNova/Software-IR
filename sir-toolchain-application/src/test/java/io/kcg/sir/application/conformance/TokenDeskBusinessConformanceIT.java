package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real-database business conformance for three things the earlier slices could not show, because each
 * only goes wrong against a real server:
 *
 * <ul>
 *   <li>a {@code Uuid} identity: MyBatis treats a {@code java.util.UUID} as a bean unless a handler
 *       binds it, and the identity strategy it was first given produced a String, so a project with a
 *       Uuid key failed on its first request;
 *   <li>a guarded update that changes nothing: the one-statement guard counts matched rows, so a row that
 *       already holds the new value must still be a match and not a refused guard;
 *   <li>a keyed load when the database breaks the declared uniqueness: the lookup must fail loudly, not
 *       pick one of the rows.
 * </ul>
 *
 * <p>This is not the ADR-017 qualification matrix and it never claims {@code QUALIFIED}; it reports
 * {@code PASSED}, {@code FAILED}, or {@code NOT_RUN} for one business slice.
 *
 * <p>Without a complete reference environment the test prints {@code NOT_RUN} and returns before any
 * side effect. It is enabled with {@code -Dkcg.token-conformance.enabled=true} and the same reference
 * environment properties as the other business slices.
 */
class TokenDeskBusinessConformanceIT {

    private static final String RELIABLE = "11111111-1111-4111-8111-111111111111";
    private static final String ALREADY_USED = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER_OWNER = "33333333-3333-4333-8333-333333333333";

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Token desk (Uuid keys, no-op guarded update, unique lookup)",
            "/valid/token-desk.sir",
            "/conformance/mysql/token-desk-ddl.sql",
            "/conformance/mysql/token-desk-seed.sql",
            "credential",
            "/api/get-credential?id=" + RELIABLE,
            "token-slice-report.txt",
            "token-slice",
            "kcg.token-conformance.enabled");

    private static final long SEEDED_ROWS = 3L;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void uuidKeysNoOpGuardedUpdatesAndBrokenUniquenessBehaveAgainstRealMysql() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[TOKEN-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[TOKEN-CONFORMANCE] The token-desk business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[TOKEN-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[TOKEN-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[TOKEN-CONFORMANCE] The token-desk business slice was NOT verified.");
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
            System.out.println("[TOKEN-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[TOKEN-CONFORMANCE] The token-desk business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "token desk business conformance FAILED:\n" + slice.report());
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

        slice.setHttpStatus("READY (200 from " + FIXTURE.readinessPath() + ")");
        try (MysqlObserver observer = slice.openObserver(runtimeUrl)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            slice.check("the fixture seed is present in the owned schema",
                    slice.rowsBeforeSize() == SEEDED_ROWS, "seededRowCount=" + slice.rowsBeforeSize());
            assertUuidKeys(slice, observer);
            assertNoOpGuardedUpdate(slice, observer);
            assertBrokenUniqueness(slice);
            assertSecretField(slice, observer);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    /** U1-U3: a credential with a Uuid key is loaded, rotated and replaced by a row with a new Uuid key. */
    private void assertUuidKeys(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        HttpAssertionClient.Response rotated = http.postJson(base + "/api/refresh", Map.of(), "{\"token\":\"" + RELIABLE + "\"}");
        slice.record("U1 POST refresh (Uuid key)", rotated);
        slice.check("a credential with a Uuid key is rotated", rotated.statusCode() / 100 == 2, "status=" + rotated.statusCode());

        String newId = BusinessSliceHarness.field(rotated.body(), "id");
        slice.check("the new credential has a different, well-formed Uuid key", isUuid(newId) && !newId.equals(RELIABLE), "newId=" + newId);
        List<String> rows = slice.rowsNow(observer);
        slice.check("the rotation stored exactly one new credential under that key",
                rows.size() == SEEDED_ROWS + 1 && rowOf(rows, newId).startsWith(newId + "|alice|"), "rows=" + rows);
        slice.check("the rotated credential is marked used and the new one is not",
                isUsed(rowOf(rows, RELIABLE)) && !isUsed(rowOf(rows, newId)), "rows=" + rows);

        HttpAssertionClient.Response replay = http.postJson(base + "/api/refresh", Map.of(), "{\"token\":\"" + RELIABLE + "\"}");
        slice.record("U2 POST refresh (replay)", replay);
        slice.expectStatus("replaying the rotated credential is refused with the declared 409", replay, 409);

        HttpAssertionClient.Response unknown = http.postJson(base + "/api/refresh", Map.of(),
                "{\"token\":\"99999999-9999-4999-8999-999999999999\"}");
        slice.record("U3 POST refresh (unknown key)", unknown);
        slice.expectStatus("an unknown Uuid key is the declared 409", unknown, 409);
        slice.check("the refusals wrote nothing", slice.rowsNow(observer).size() == SEEDED_ROWS + 1, "rows=" + slice.rowsNow(observer));
    }

    /**
     * N1-N2: setting a column to the value it already has still matches the row. The default of the MySQL
     * driver counts matched rows; a driver told to count changed rows would answer 409 here, which is why
     * this is checked against the real driver and not assumed.
     */
    private void assertNoOpGuardedUpdate(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        HttpAssertionClient.Response touched = http.postJson(base + "/api/touch", Map.of(), "{\"token\":\"" + ALREADY_USED + "\"}");
        slice.record("N1 POST touch (the update changes nothing)", touched);
        slice.check("an update that changes nothing is a match, not a refused guard",
                touched.statusCode() / 100 == 2, "status=" + touched.statusCode());
        slice.check("the touched credential is as it was", isUsed(rowOf(slice.rowsNow(observer), ALREADY_USED)),
                "rows=" + slice.rowsNow(observer));

        HttpAssertionClient.Response refused = http.postJson(base + "/api/touch", Map.of(), "{\"token\":\"" + OTHER_OWNER + "\"}");
        slice.record("N2 POST touch (guard false)", refused);
        slice.expectStatus("a row the guard does not accept is the declared 409", refused, 409);
        slice.check("the refused credential was not written", !isUsed(rowOf(slice.rowsNow(observer), OTHER_OWNER)),
                "rows=" + slice.rowsNow(observer));
    }

    /** K1-K3: a keyed load answers a hit, a miss, and, when the database holds two rows for the key, an error. */
    private void assertBrokenUniqueness(BusinessSliceHarness slice) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        HttpAssertionClient.Response hit = http.get(base + "/api/find-account?name=ann", Map.of());
        slice.record("K1 GET find-account (hit)", hit);
        slice.expectStatus("a unique name finds its row", hit, 200);
        slice.expectFields("the row found is the one with that name", hit, Map.of("name", "ann", "id", "1"));

        HttpAssertionClient.Response miss = http.get(base + "/api/find-account?name=nobody", Map.of());
        slice.record("K2 GET find-account (miss)", miss);
        slice.expectStatus("a name with no row is the declared 404", miss, 404);

        HttpAssertionClient.Response duplicate = http.get(base + "/api/find-account?name=dup", Map.of());
        slice.record("K3 GET find-account (two rows)", duplicate);
        slice.expectStatus("a name held by two rows is an internal error, not an arbitrary one of them", duplicate, 500);
    }

    /**
     * S1-S5: a secret is stored as a digest, and only the right candidate matches it. A wrong password
     * and an unknown account answer the same declared 401, so the response does not tell which it was.
     */
    private void assertSecretField(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        HttpAssertionClient.Response registered = http.postJson(base + "/api/register", Map.of(),
                "{\"name\":\"zoe\",\"password\":\"correct horse battery\"}");
        slice.record("S1 POST register", registered);
        slice.check("registering stores an account", registered.statusCode() / 100 == 2, "status=" + registered.statusCode());
        slice.check("the response does not carry the password or its digest",
                !registered.body().contains("correct horse") && !registered.body().toLowerCase().contains("password"),
                "body=" + registered.body());

        List<String> accounts = slice.rowsOf(observer, "account");
        String stored = accounts.stream().filter(row -> row.contains("|zoe|")).findFirst().orElse("<absent>");
        slice.check("the stored text is a salted digest and does not contain the password",
                stored.contains("|pbkdf2-sha256$") && !stored.contains("correct horse"), "row=" + stored);

        HttpAssertionClient.Response right = http.postJson(base + "/api/login", Map.of(),
                "{\"name\":\"zoe\",\"password\":\"correct horse battery\"}");
        slice.record("S2 POST login (right password)", right);
        slice.expectStatus("the right password logs in", right, 200);
        slice.expectFields("the login answers the account without its secret", right, Map.of("name", "zoe"));

        HttpAssertionClient.Response wrong = http.postJson(base + "/api/login", Map.of(),
                "{\"name\":\"zoe\",\"password\":\"correct horse batterz\"}");
        slice.record("S3 POST login (wrong password)", wrong);
        slice.expectStatus("a wrong password is the declared 401", wrong, 401);

        HttpAssertionClient.Response unknown = http.postJson(base + "/api/login", Map.of(),
                "{\"name\":\"nobody\",\"password\":\"correct horse battery\"}");
        slice.record("S4 POST login (unknown account)", unknown);
        slice.expectStatus("an unknown account is the declared 401", unknown, 401);
        slice.check("a wrong password and an unknown account are answered identically",
                wrong.body().equals(unknown.body()), "wrong=" + wrong.body() + " unknown=" + unknown.body());

        HttpAssertionClient.Response unusable = http.postJson(base + "/api/login", Map.of(),
                "{\"name\":\"ann\",\"password\":\"not-a-digest\"}");
        slice.record("S5 POST login (stored text is not a digest)", unusable);
        slice.expectStatus("a stored text that is not a digest never matches, even when the candidate equals it", unusable, 401);
    }

    private static boolean isUuid(String text) {
        try {
            return !text.isEmpty() && UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    private static String rowOf(List<String> rows, String id) {
        return rows.stream().filter(row -> row.startsWith(id + "|")).findFirst().orElse("<absent>");
    }

    /** The third column is the used flag; the driver renders a TINYINT(1) as 1 or true. */
    private static boolean isUsed(String row) {
        String[] columns = row.split("\\|");
        return columns.length > 2 && ("1".equals(columns[2]) || "true".equalsIgnoreCase(columns[2]));
    }
}
