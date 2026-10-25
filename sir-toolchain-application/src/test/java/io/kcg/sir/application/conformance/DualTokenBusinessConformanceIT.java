package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real-database business conformance for the dual-token login as written out in {@code dual-token-written.sir}
 * (FeatureUseTest keeps it the same software as {@code use DualTokenLogin}, down to the generated project). The seven behaviours of a dual-token
 * login are each a request here: a pair of tokens on login, a wrong password and an unknown account answered alike,
 * the access token authenticating a protected route while the refresh token does not, an expired access token refused,
 * a refresh that replaces the pair, a replayed refresh refused (also when several arrive at the same moment), and a
 * logout after which the refresh token no longer works. Every state change is read back over an independent connection.
 *
 * <p>This is not the ADR-017 qualification matrix and it never claims {@code QUALIFIED}; it reports {@code PASSED},
 * {@code FAILED}, or {@code NOT_RUN} for one business slice. Without a complete reference environment the test prints
 * {@code NOT_RUN} and returns before any side effect. It is enabled with {@code -Dkcg.dual-token-conformance.enabled=true}
 * and the same reference environment properties as the other business slices.
 */
class DualTokenBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Dual token (use DualTokenLogin, written out)",
            "/valid/dual-token-written.sir",
            "/conformance/mysql/dual-token-ddl.sql",
            "/conformance/mysql/dual-token-seed.sql",
            "user",
            "/api/get-user?id=1",
            "dual-token-slice-report.txt",
            "dual-token-slice",
            "kcg.dual-token-conformance.enabled");

    private static final long SEEDED_ROWS = 1L;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void theDualTokenPatternBehavesLikeADualTokenLoginAgainstRealMysql() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[DUAL-TOKEN-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[DUAL-TOKEN-CONFORMANCE] The dual-token business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[DUAL-TOKEN-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[DUAL-TOKEN-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[DUAL-TOKEN-CONFORMANCE] The dual-token business slice was NOT verified.");
            return;
        }

        var candidate = DualTokenCandidateSupport.read(System::getProperty);
        BusinessSliceHarness slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            slice.verify(() -> execute(slice, candidate));
        } catch (java.sql.SQLException unreachable) {
            // The harness's own connection to the reference database is infrastructure, not the
            // product under test: an unreachable server yields no verdict rather than a failure.
            slice.setNotRunReason("the reference database became unreachable: "
                    + BusinessSliceHarness.describe(unreachable));
        } finally {
            slice.teardown();
            slice.writeReport();
            if (candidate.isPresent()) DualTokenCandidateSupport.writeEvidence(candidate.orElseThrow(), slice);
        }
        if (slice.notRunReason() != null) {
            System.out.println("[DUAL-TOKEN-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[DUAL-TOKEN-CONFORMANCE] The dual-token business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "dual-token business conformance FAILED:\n" + slice.report());
    }

    /** Runs the slice end to end. Every failure is recorded; none is swallowed. */
    private void execute(BusinessSliceHarness slice, java.util.Optional<DualTokenCandidateSupport.Input> candidate) throws Exception {
        MysqlRuntimeJdbcUrl runtimeUrl = slice.runtimeUrl();
        slice.initializeRedaction(runtimeUrl);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null) {
            return;
        }

        if (candidate.isPresent()) {
            slice.reportLine("slmMysqlVersion=" + slice.databaseVersion());
            var input = candidate.orElseThrow();
            var files = slice.compileAndMaterialize(input.sources());
            if (files.isEmpty()) return;
            DualTokenCandidateSupport.verifyGenerated(input, slice, files);
        } else if (slice.compileAndMaterialize().isEmpty()) {
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
            assertDualToken(slice, observer);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    private static final int RACERS = 6;

    private void assertDualToken(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        String zoeId = BusinessSliceHarness.field(register(slice, http, base, "zoe").body(), "id");

        // D1: the right password gets a pair of tokens; a wrong password and an unknown account get the same answer.
        HttpAssertionClient.Response login = http.postJson(base + "/api/login", Map.of(), credentials("zoe", "pw-zoe"));
        slice.record("D1 POST login", login);
        slice.check("the right password logs in", login.statusCode() / 100 == 2, "status=" + login.statusCode());
        String access = BusinessSliceHarness.field(login.body(), "accessToken");
        String refresh = BusinessSliceHarness.field(login.body(), "refreshToken");
        slice.check("the login answers an access token and a different refresh token",
                isUuid(access) && isUuid(refresh) && !access.equals(refresh), "access=" + access + " refresh=" + refresh);
        slice.check("the access token lives about fifteen minutes and the refresh token about seven days",
                withinMinutes(login.body(), "accessExpiresAt", 14, 16) && withinMinutes(login.body(), "refreshExpiresAt", 7 * 24 * 60 - 5, 7 * 24 * 60 + 5),
                "body=" + login.body());
        slice.check("each token is stored for the account that logged in",
                slice.rowsOf(observer, "access_token").stream().anyMatch(row -> row.startsWith(access + "|" + zoeId + "|"))
                        && slice.rowsOf(observer, "refresh_token").stream().anyMatch(row -> row.startsWith(refresh + "|" + zoeId + "|0|")),
                "access_token=" + slice.rowsOf(observer, "access_token") + " refresh_token=" + slice.rowsOf(observer, "refresh_token"));
        HttpAssertionClient.Response wrong = http.postJson(base + "/api/login", Map.of(), credentials("zoe", "wrong"));
        HttpAssertionClient.Response unknown = http.postJson(base + "/api/login", Map.of(), credentials("nobody", "wrong"));
        slice.record("D1 POST login (wrong password)", wrong);
        slice.record("D1 POST login (unknown account)", unknown);
        slice.expectStatus("a wrong password is the declared 401", wrong, 401);
        slice.expectStatus("an unknown account is the declared 401", unknown, 401);
        slice.check("a wrong password and an unknown account are answered identically", wrong.body().equals(unknown.body()),
                "wrong=" + wrong.body() + " unknown=" + unknown.body());

        // D2: the access token authenticates a protected route as its owner; nothing else does.
        HttpAssertionClient.Response bare = note(http, base, null, "bare");
        slice.record("D2 POST post-note (no credential)", bare);
        slice.expectStatus("a request without a credential is refused with 401", bare, 401);
        HttpAssertionClient.Response viaAccess = note(http, base, "Bearer " + access, "with access token");
        slice.record("D2 POST post-note (access token)", viaAccess);
        slice.check("the access token authenticates", viaAccess.statusCode() / 100 == 2, "status=" + viaAccess.statusCode());
        slice.check("the note was written as the token's owner",
                slice.rowsOf(observer, "note").stream().anyMatch(row -> row.endsWith("|with access token") && row.contains("|" + zoeId + "|")),
                "notes=" + slice.rowsOf(observer, "note"));
        HttpAssertionClient.Response viaRefresh = note(http, base, "Bearer " + refresh, "with refresh token");
        slice.record("D2 POST post-note (refresh token as credential)", viaRefresh);
        slice.expectStatus("a refresh token does not authenticate a request", viaRefresh, 401);
        slice.check("the refusals wrote no note", slice.rowsOf(observer, "note").size() == 1, "notes=" + slice.rowsOf(observer, "note"));

        // D3: an access token that has run out is refused.
        slice.fixtureStatement("UPDATE access_token SET expires_at = '2000-01-01 00:00:00' WHERE id = '" + access + "'");
        HttpAssertionClient.Response expired = note(http, base, "Bearer " + access, "after expiry");
        slice.record("D3 POST post-note (expired access token)", expired);
        slice.expectStatus("an expired access token is refused with 401", expired, 401);

        // D4: a refresh replaces the pair and spends the old refresh token.
        HttpAssertionClient.Response refreshed = http.postJson(base + "/api/refresh", Map.of(), refreshBody(refresh));
        slice.record("D4 POST refresh", refreshed);
        slice.check("a refresh token is exchanged for a new pair", refreshed.statusCode() / 100 == 2, "status=" + refreshed.statusCode());
        String access2 = BusinessSliceHarness.field(refreshed.body(), "accessToken");
        String refresh2 = BusinessSliceHarness.field(refreshed.body(), "refreshToken");
        slice.check("the new pair differs from the old one", isUuid(access2) && isUuid(refresh2) && !access2.equals(access) && !refresh2.equals(refresh),
                "body=" + refreshed.body());
        slice.check("the old refresh token is spent and the new one is not",
                refreshRow(slice, observer, refresh).contains("|1|") && refreshRow(slice, observer, refresh2).contains("|0|"),
                "refresh_token=" + slice.rowsOf(observer, "refresh_token"));
        HttpAssertionClient.Response viaNew = note(http, base, "Bearer " + access2, "with new access token");
        slice.record("D4 POST post-note (new access token)", viaNew);
        slice.check("the new access token authenticates", viaNew.statusCode() / 100 == 2, "status=" + viaNew.statusCode());

        // D5: replaying the spent refresh token is refused and writes nothing.
        int accessRows = slice.rowsOf(observer, "access_token").size();
        int refreshRows = slice.rowsOf(observer, "refresh_token").size();
        HttpAssertionClient.Response replay = http.postJson(base + "/api/refresh", Map.of(), refreshBody(refresh));
        slice.record("D5 POST refresh (replay)", replay);
        slice.expectStatus("a spent refresh token is refused with the declared 401", replay, 401);
        slice.check("the replay wrote no token",
                slice.rowsOf(observer, "access_token").size() == accessRows && slice.rowsOf(observer, "refresh_token").size() == refreshRows,
                "access_token=" + slice.rowsOf(observer, "access_token").size() + " refresh_token=" + slice.rowsOf(observer, "refresh_token").size());

        // D6: many refreshes of the same token at once rotate it exactly once.
        List<HttpAssertionClient.Response> raced = raceRefresh(http, base, refresh2, RACERS);
        for (int index = 0; index < raced.size(); index++) {
            slice.record("D6 POST refresh (racer " + index + ")", raced.get(index));
        }

        List<Integer> statuses = new ArrayList<>();
        raced.forEach(response -> statuses.add(response.statusCode()));
        statuses.sort(Integer::compareTo);
        slice.check("exactly one of " + RACERS + " simultaneous refreshes succeeds",
                statuses.stream().filter(status -> status / 100 == 2).count() == 1, "statuses=" + statuses);
        slice.check("every other simultaneous refresh is refused with 401",
                statuses.stream().filter(status -> status == 401).count() == RACERS - 1, "statuses=" + statuses);
        slice.check("the race created exactly one new pair of tokens",
                slice.rowsOf(observer, "access_token").size() == accessRows + 1 && slice.rowsOf(observer, "refresh_token").size() == refreshRows + 1,
                "access_token=" + slice.rowsOf(observer, "access_token").size() + " refresh_token=" + slice.rowsOf(observer, "refresh_token").size());

        // D7: logging out spends the refresh token.
        HttpAssertionClient.Response winner = raced.stream().filter(response -> response.statusCode() / 100 == 2).findFirst().orElse(null);
        if (winner != null) {
            String refresh3 = BusinessSliceHarness.field(winner.body(), "refreshToken");
            HttpAssertionClient.Response out = http.postJson(base + "/api/logout", Map.of(), refreshBody(refresh3));
            slice.record("D7 POST logout", out);
            slice.expectStatus("logging out answers 200", out, 200);
            HttpAssertionClient.Response after = http.postJson(base + "/api/refresh", Map.of(), refreshBody(refresh3));
            slice.record("D7 POST refresh (after logout)", after);
            slice.expectStatus("a refresh token that was logged out is refused with 401", after, 401);
        }
    }

    private static HttpAssertionClient.Response register(BusinessSliceHarness slice, HttpAssertionClient http, String base, String name) throws Exception {
        HttpAssertionClient.Response registered = http.postJson(base + "/api/register", Map.of(), credentials(name, "pw-" + name));
        slice.record("D0 POST register (" + name + ")", registered);
        slice.check("registering " + name + " stores an account", registered.statusCode() / 100 == 2, "status=" + registered.statusCode());
        return registered;
    }

    private static String credentials(String name, String password) {
        return "{\"name\":\"" + name + "\",\"password\":\"" + password + "\"}";
    }

    private static String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private static HttpAssertionClient.Response note(HttpAssertionClient http, String base, String authorization, String text) throws Exception {
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        return http.postJson(base + "/api/post-note", headers, "{\"text\":\"" + text + "\"}");
    }

    private static String refreshRow(BusinessSliceHarness slice, MysqlObserver observer, String token) throws java.sql.SQLException {
        return slice.rowsOf(observer, "refresh_token").stream().filter(row -> row.startsWith(token + "|")).findFirst().orElse("<absent>");
    }

    /**
     * Sends the same refresh from several threads released together by a barrier, so none can finish before the others have
     * started; the rotation is one guarded statement, so the database decides the winner.
     */
    private static List<HttpAssertionClient.Response> raceRefresh(HttpAssertionClient http, String base, String refreshToken, int racers) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(racers);
        ExecutorService executor = Executors.newFixedThreadPool(racers);
        try {
            List<Callable<HttpAssertionClient.Response>> tasks = new ArrayList<>();
            for (int index = 0; index < racers; index++) {
                tasks.add(() -> {
                    barrier.await();
                    return http.postJson(base + "/api/refresh", Map.of(), refreshBody(refreshToken));
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

    /** Whether the named ISO instant of a response body lies between the two numbers of minutes from now. */
    private static boolean withinMinutes(String body, String field, long minMinutes, long maxMinutes) {
        try {
            Instant at = Instant.parse(BusinessSliceHarness.field(body, field));
            Instant now = Instant.now();
            return at.isAfter(now.plusSeconds(minMinutes * 60)) && at.isBefore(now.plusSeconds(maxMinutes * 60));
        } catch (java.time.format.DateTimeParseException notAnInstant) {
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
