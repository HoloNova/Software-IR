package kcg.acceptance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The dual-token login requirement, checked against a deployed instance over real HTTP.
 *
 * <p>D1 a login answers a pair of different tokens (access about fifteen minutes, refresh about seven days) and a wrong
 * password is answered exactly like an unknown account; D2 the access token authenticates a protected write as its owner
 * while no credential and the refresh token do not; D3 an expired access token is refused; D4 a refresh replaces the pair
 * and spends the old refresh token; D5 replaying a spent refresh token is refused and writes nothing; D6 of several
 * simultaneous refreshes of one token exactly one succeeds; D7 after logout the refresh token no longer works.
 *
 * <p>Inputs (system properties): {@code kcg.baseUrl}; {@code kcg.jdbcUrl}, {@code kcg.jdbcUser} and
 * {@code kcg.jdbcPasswordFile} for an independent verification connection to the instance's own database (also used to
 * expire one token for D3); {@code kcg.reportDir} for {@code acceptance-report.tsv}. The physical names below are part of
 * this fixed requirement fixture. The suite never reads the SIR or the generated application. It writes business data, so
 * it runs only on a disposable instance; a random account name lets it run again on the same instance.
 */
class DualTokenAcceptanceTest {

    private static final int RACERS = 6;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final List<String> report = new ArrayList<>();

    private String base;

    private Connection db;

    @Test
    void dualTokenLoginMeetsItsRequirement() throws Exception {
        base = required("kcg.baseUrl");
        String password = Files.readString(Path.of(required("kcg.jdbcPasswordFile"))).strip();
        try (Connection connection = DriverManager.getConnection(required("kcg.jdbcUrl"), required("kcg.jdbcUser"), password)) {
            db = connection;
            run();
        } finally {
            writeReport();
        }
        List<String> failed = report.stream().filter(line -> line.startsWith("FAILED")).toList();
        assertTrue(failed.isEmpty(), () -> "dual-token acceptance FAILED:\n" + String.join("\n", failed));
    }

    private void run() throws Exception {
        String name = "acceptance-" + UUID.randomUUID().toString().substring(0, 8);

        // D0: an account to log in with.
        Response registered = post("/api/register", Map.of("name", name, "password", "pw-" + name), null);
        check("D0", "registering an account succeeds", registered.status / 100 == 2, registered);
        long account = registered.json().path("id").asLong();

        // D1: a pair on the right password; a wrong password and an unknown account answered alike.
        Response login = post("/api/login", Map.of("name", name, "password", "pw-" + name), null);
        check("D1", "the right password logs in", login.status / 100 == 2, login);
        String access = login.json().path("accessToken").asText();
        String refresh = login.json().path("refreshToken").asText();
        check("D1", "login answers an access token and a different refresh token", isUuid(access) && isUuid(refresh) && !access.equals(refresh), login);
        check("D1", "the access token lives about fifteen minutes and the refresh token about seven days",
                within(login.json(), "accessExpiresAt", 14, 16) && within(login.json(), "refreshExpiresAt", 7 * 24 * 60 - 5, 7 * 24 * 60 + 5), login);
        check("D1", "each token is stored for the account that logged in",
                count("SELECT COUNT(*) FROM access_token WHERE id = ? AND owner_id = ?", access, account) == 1
                        && count("SELECT COUNT(*) FROM refresh_token WHERE id = ? AND owner_id = ? AND used = FALSE", refresh, account) == 1, null);
        Response wrong = post("/api/login", Map.of("name", name, "password", "wrong"), null);
        Response unknown = post("/api/login", Map.of("name", "nobody-" + UUID.randomUUID(), "password", "wrong"), null);
        check("D1", "a wrong password is the declared 401", wrong.status == 401, wrong);
        check("D1", "an unknown account is the declared 401", unknown.status == 401, unknown);
        check("D1", "a wrong password and an unknown account are answered identically", wrong.body.equals(unknown.body),
                "wrong=" + wrong.body + " unknown=" + unknown.body);

        // D2: the access token authenticates a protected write as its owner; nothing else does.
        Response bare = note(null, "bare");
        check("D2", "a request without a credential is refused with 401", bare.status == 401, bare);
        Response viaAccess = note(access, "with access token");
        check("D2", "the access token authenticates", viaAccess.status / 100 == 2, viaAccess);
        check("D2", "the note is written as the token's owner",
                count("SELECT COUNT(*) FROM note WHERE author_id = ? AND text = ?", account, "with access token") == 1, null);
        Response viaRefresh = note(refresh, "with refresh token");
        check("D2", "a refresh token does not authenticate a request", viaRefresh.status == 401, viaRefresh);
        check("D2", "the refusals wrote no note", count("SELECT COUNT(*) FROM note WHERE author_id = ?", account) == 1, null);

        // D3: an access token that has run out is refused.
        try (PreparedStatement expire = db.prepareStatement("UPDATE access_token SET expires_at = '2000-01-01 00:00:00' WHERE id = ?")) {
            expire.setString(1, access);
            expire.executeUpdate();
        }
        Response expired = note(access, "after expiry");
        check("D3", "an expired access token is refused with 401", expired.status == 401, expired);

        // D4: a refresh replaces the pair and spends the old refresh token.
        Response refreshed = post("/api/refresh", Map.of("refreshToken", refresh), null);
        check("D4", "a refresh token is exchanged for a new pair", refreshed.status / 100 == 2, refreshed);
        String access2 = refreshed.json().path("accessToken").asText();
        String refresh2 = refreshed.json().path("refreshToken").asText();
        check("D4", "the new pair differs from the old one",
                isUuid(access2) && isUuid(refresh2) && !access2.equals(access) && !refresh2.equals(refresh), refreshed);
        check("D4", "the old refresh token is spent and the new one is not",
                count("SELECT COUNT(*) FROM refresh_token WHERE id = ? AND used = TRUE", refresh) == 1
                        && count("SELECT COUNT(*) FROM refresh_token WHERE id = ? AND used = FALSE", refresh2) == 1, null);
        Response viaNew = note(access2, "with new access token");
        check("D4", "the new access token authenticates", viaNew.status / 100 == 2, viaNew);

        // D5: replaying the spent refresh token is refused and writes nothing.
        long accessRows = count("SELECT COUNT(*) FROM access_token WHERE owner_id = ?", account);
        long refreshRows = count("SELECT COUNT(*) FROM refresh_token WHERE owner_id = ?", account);
        Response replay = post("/api/refresh", Map.of("refreshToken", refresh), null);
        check("D5", "a spent refresh token is refused with the declared 401", replay.status == 401, replay);
        check("D5", "the replay wrote no token", count("SELECT COUNT(*) FROM access_token WHERE owner_id = ?", account) == accessRows
                && count("SELECT COUNT(*) FROM refresh_token WHERE owner_id = ?", account) == refreshRows, null);

        // D6: several refreshes of the same token at the same moment rotate it exactly once.
        List<Response> raced = race(refresh2);
        List<Integer> statuses = raced.stream().map(r -> r.status).sorted().toList();
        check("D6", "exactly one of " + RACERS + " simultaneous refreshes succeeds", statuses.stream().filter(s -> s / 100 == 2).count() == 1, "statuses=" + statuses);
        check("D6", "every other simultaneous refresh is refused with 401", statuses.stream().filter(s -> s == 401).count() == RACERS - 1, "statuses=" + statuses);
        check("D6", "the race created exactly one new pair of tokens",
                count("SELECT COUNT(*) FROM access_token WHERE owner_id = ?", account) == accessRows + 1
                        && count("SELECT COUNT(*) FROM refresh_token WHERE owner_id = ?", account) == refreshRows + 1, null);

        // D7: logging out spends the refresh token.
        Response winner = raced.stream().filter(r -> r.status / 100 == 2).findFirst().orElse(null);
        check("D7", "a refresh token from the race is available to log out with", winner != null, "statuses=" + statuses);
        if (winner != null) {
            String refresh3 = winner.json().path("refreshToken").asText();
            Response out = post("/api/logout", Map.of("refreshToken", refresh3), null);
            check("D7", "logging out answers 200", out.status == 200, out);
            Response after = post("/api/refresh", Map.of("refreshToken", refresh3), null);
            check("D7", "a refresh token that was logged out is refused with 401", after.status == 401, after);
        }
    }

    private record Response(int status, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (Exception notJson) {
                return JSON.missingNode();
            }
        }

        @Override
        public String toString() {
            return "status=" + status + " body=" + body;
        }
    }

    private Response post(String path, Map<String, String> body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response note(String bearer, String text) throws Exception {
        return post("/api/post-note", Map.of("text", text), bearer);
    }

    /** The same refresh from several threads released together, so none finishes before the others have started. */
    private List<Response> race(String refreshToken) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(RACERS);
        ExecutorService executor = Executors.newFixedThreadPool(RACERS);
        try {
            List<Callable<Response>> tasks = new ArrayList<>();
            for (int index = 0; index < RACERS; index++) {
                tasks.add(() -> {
                    barrier.await();
                    return post("/api/refresh", Map.of("refreshToken", refreshToken), null);
                });
            }
            List<Response> responses = new ArrayList<>();
            for (Future<Response> result : executor.invokeAll(tasks)) {
                responses.add(result.get());
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private long count(String sql, Object... parameters) throws Exception {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private void check(String step, String requirement, boolean met, Object evidence) {
        String detail = evidence == null ? "" : String.valueOf(evidence).replaceAll("\\s+", " ");
        report.add(String.join("\t", met ? "PASSED" : "FAILED", step, requirement, met ? "" : detail));
    }

    private void writeReport() throws Exception {
        Path directory = Path.of(System.getProperty("kcg.reportDir", "target"));
        Files.createDirectories(directory);
        List<String> lines = new ArrayList<>(List.of("outcome\tstep\trequirement\tdetail"));
        lines.addAll(report);
        Files.write(directory.resolve("acceptance-report.tsv"), lines);
    }

    private static boolean within(JsonNode body, String field, long minMinutes, long maxMinutes) {
        try {
            Instant at = Instant.parse(body.path(field).asText());
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

    private static String required(String property) {
        String value = System.getProperty(property, "");
        assertTrue(!value.isBlank(), "set -D" + property);
        return value;
    }
}
