package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real-database business conformance for authentication by a credential entity: a login issues a token (a
 * row of the credential entity), and a request carrying it in an {@code Authorization: Bearer} header is
 * authenticated as the token's owner. The generated authenticator reads the header, the table and the
 * clock, so each way a request can fail to be authenticated is a separate request here, and the rows each
 * accepted request wrote are read back over an independent connection to show whose identity it ran as.
 *
 * <p>This is not the ADR-017 qualification matrix and it never claims {@code QUALIFIED}; it reports
 * {@code PASSED}, {@code FAILED}, or {@code NOT_RUN} for one business slice. Without a complete reference
 * environment the test prints {@code NOT_RUN} and returns before any side effect. It is enabled with
 * {@code -Dkcg.bearer-conformance.enabled=true} and the same reference environment properties as the
 * other business slices.
 */
class BearerCredentialBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Bearer credential (login issues a token, a Bearer header authenticates)",
            "/valid/bearer-credential.sir",
            "/conformance/mysql/bearer-credential-ddl.sql",
            "/conformance/mysql/bearer-credential-seed.sql",
            "user",
            "/api/get-user?id=1",
            "bearer-slice-report.txt",
            "bearer-slice",
            "kcg.bearer-conformance.enabled");

    private static final long SEEDED_ROWS = 1L;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void aTokenIssuedByLoginAuthenticatesRequestsAsItsOwnerAgainstRealMysql() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[BEARER-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[BEARER-CONFORMANCE] The bearer-credential business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[BEARER-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[BEARER-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[BEARER-CONFORMANCE] The bearer-credential business slice was NOT verified.");
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
            System.out.println("[BEARER-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[BEARER-CONFORMANCE] The bearer-credential business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "bearer credential business conformance FAILED:\n" + slice.report());
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

        slice.useActorMode("external");
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
            assertBearerAuthentication(slice, observer);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    private void assertBearerAuthentication(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        String zoeId = register(slice, http, base, "zoe");
        String annId = register(slice, http, base, "ann");
        String zoeToken = login(slice, http, base, observer, "zoe", zoeId);
        String annToken = login(slice, http, base, observer, "ann", annId);

        // B3: no credential, no authentication, and nothing is written.
        HttpAssertionClient.Response bare = http.postJson(base + "/api/post-note", Map.of(), "{\"text\":\"bare\"}");
        slice.record("B3 POST post-note (no Authorization header)", bare);
        slice.expectStatus("a request without a credential is refused with 401", bare, 401);
        slice.check("the refusal wrote no note", slice.rowsOf(observer, "note").isEmpty(), "notes=" + slice.rowsOf(observer, "note"));

        // B4: a valid token authenticates as its owner, whichever of two accounts holds it.
        HttpAssertionClient.Response zoeNote = post(http, base, "Bearer " + zoeToken, "zoe's note");
        slice.record("B4 POST post-note (zoe's token)", zoeNote);
        slice.check("a valid token authenticates", zoeNote.statusCode() / 100 == 2, "status=" + zoeNote.statusCode());
        HttpAssertionClient.Response annNote = post(http, base, "bearer " + annToken, "ann's note");
        slice.record("B4 POST post-note (ann's token, lower-case scheme)", annNote);
        slice.check("the scheme name is not case sensitive", annNote.statusCode() / 100 == 2, "status=" + annNote.statusCode());
        List<String> notes = slice.rowsOf(observer, "note");
        slice.check("each note was written as the owner of the token it carried",
                notes.stream().anyMatch(row -> row.endsWith("|zoe's note") && row.contains("|" + zoeId + "|"))
                        && notes.stream().anyMatch(row -> row.endsWith("|ann's note") && row.contains("|" + annId + "|")),
                "notes=" + notes);

        // B5: every other way of presenting a credential is refused, and nothing is written.
        String unknown = "99999999-9999-4999-8999-999999999999";
        for (String header : List.of("Basic " + zoeToken, "Bearer not-a-uuid", "Bearer " + unknown, "Bearer", zoeToken)) {
            HttpAssertionClient.Response refused = post(http, base, header, "refused");
            slice.record("B5 POST post-note (Authorization: " + header + ")", refused);
            slice.expectStatus("'" + header + "' is refused with 401", refused, 401);
        }

        slice.check("the refusals wrote no note", slice.rowsOf(observer, "note").size() == 2, "notes=" + slice.rowsOf(observer, "note"));

        // B6: logging out ends the token, and only that one.
        HttpAssertionClient.Response out = http.postJson(base + "/api/logout", Map.of(), "{\"token\":\"" + zoeToken + "\"}");
        slice.record("B6 POST logout", out);
        slice.expectStatus("logging out answers 200", out, 200);
        HttpAssertionClient.Response after = post(http, base, "Bearer " + zoeToken, "after logout");
        slice.record("B6 POST post-note (zoe's token after logout)", after);
        slice.expectStatus("a token that was logged out is refused with 401", after, 401);
        HttpAssertionClient.Response stillAnn = post(http, base, "Bearer " + annToken, "ann again");
        slice.record("B6 POST post-note (ann's token after zoe logged out)", stillAnn);
        slice.check("another account's token is unaffected", stillAnn.statusCode() / 100 == 2, "status=" + stillAnn.statusCode());
        HttpAssertionClient.Response nobody = http.postJson(base + "/api/logout", Map.of(), "{\"token\":\"" + unknown + "\"}");
        slice.record("B6 POST logout (unknown token)", nobody);
        slice.expectStatus("logging out a token that does not exist is the declared 401", nobody, 401);
        slice.check("only the two accepted notes and the one after remain",
                slice.rowsOf(observer, "note").size() == 3, "notes=" + slice.rowsOf(observer, "note"));
    }

    private static String register(BusinessSliceHarness slice, HttpAssertionClient http, String base, String name) throws Exception {
        HttpAssertionClient.Response registered = http.postJson(base + "/api/register", Map.of(),
                "{\"name\":\"" + name + "\",\"password\":\"pw-" + name + "\"}");
        slice.record("B1 POST register (" + name + ")", registered);
        slice.check("registering " + name + " stores an account", registered.statusCode() / 100 == 2, "status=" + registered.statusCode());
        return BusinessSliceHarness.field(registered.body(), "id");
    }

    private static String login(BusinessSliceHarness slice, HttpAssertionClient http, String base, MysqlObserver observer,
            String name, String accountId) throws Exception {
        HttpAssertionClient.Response signedIn = http.postJson(base + "/api/login", Map.of(),
                "{\"name\":\"" + name + "\",\"password\":\"pw-" + name + "\"}");
        slice.record("B2 POST login (" + name + ")", signedIn);
        slice.expectStatus("the right password logs " + name + " in (a login creates the token row, so it answers 201)", signedIn, 201);
        String token = BusinessSliceHarness.field(signedIn.body(), "id");
        slice.check("the login answers a well-formed token", isUuid(token), "token=" + token);
        String stored = slice.rowsOf(observer, "access_token").stream().filter(row -> row.startsWith(token + "|")).findFirst().orElse("<absent>");
        slice.check("the token is stored for " + name + " and expires in about fifteen minutes",
                stored.startsWith(token + "|" + accountId + "|") && expiresWithin(stored, 14, 16), "row=" + stored);
        HttpAssertionClient.Response wrong = http.postJson(base + "/api/login", Map.of(),
                "{\"name\":\"" + name + "\",\"password\":\"wrong\"}");
        slice.record("B2 POST login (" + name + ", wrong password)", wrong);
        slice.expectStatus("a wrong password is the declared 401", wrong, 401);
        return token;
    }

    private static HttpAssertionClient.Response post(HttpAssertionClient http, String base, String authorization, String text) throws Exception {
        return http.postJson(base + "/api/post-note", Map.of("Authorization", authorization), "{\"text\":\"" + text + "\"}");
    }

    /** The third column is the stored expiry, read as a local timestamp of the observer's session (UTC in the harness). */
    private static boolean expiresWithin(String row, long minMinutes, long maxMinutes) {
        String[] columns = row.split("\\|");
        if (columns.length < 3) {
            return false;
        }

        try {
            Instant expires = java.time.LocalDateTime.parse(columns[2].replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC);
            Instant now = Instant.now();
            return expires.isAfter(now.plusSeconds(minMinutes * 60)) && expires.isBefore(now.plusSeconds(maxMinutes * 60));
        } catch (java.time.format.DateTimeParseException notATimestamp) {
            return false;
        }
    }

    private static boolean isUuid(String text) {
        try {
            return !text.isEmpty() && UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}
