package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Actual generated HTTP/JDBC behavior; the separate deployment smoke verifies product DDL. */
class MoneyBusinessConformanceIT {
    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "G10 exact Money, PATCH, atomic deltas and non-strict sessions", "/valid/money.sir",
            "/conformance/mysql/money-ddl.sql", "/conformance/mysql/money-seed.sql", "wallet", "/api/wallet/show?id=1",
            "money-report.txt", "money", "kcg.money-conformance.enabled");
    private static final String MAX = "99999999999999999.99";

    @Test void moneyAgainstRealMysql() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[MONEY-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        try { execute(slice); }
        finally { slice.teardown(); slice.writeReport(); }
        System.out.println(slice.report());
        assertTrue(slice.passed(), slice::report);
    }

    private void execute(BusinessSliceHarness s) throws Exception {
        var url = s.runtimeUrl();
        s.initializeRedaction(url);
        s.prepareSchemaAndFixtures();
        if (s.notRunReason() != null || s.compileAndMaterialize().isEmpty()) return;
        var jar = s.buildAndLocateJar(url);
        if (jar == null || !s.startAndWaitUntilReady(jar)) return;
        s.setHttpStatus("READY (Money wallet query)");
        try (var observer = s.openObserver(url)) {
            s.setRowsBefore(s.rowsNow(observer));
            var http = new HttpAssertionClient();
            ordinaryWrites(s, observer, http);
            patches(s, observer, http);
            atomicWrites(s, observer, http);
            overflow(s, observer, http);
            // Fixed pool initialization affects the actual write connections; never SET GLOBAL or a second probe connection.
            s.useNonStrictSqlModeFixture();
            if (!s.rebuildAndRestart(url)) return;
            nonStrict(s, observer, http);
            s.setRowsAfter(s.rowsNow(observer));
        }
    }

    private void ordinaryWrites(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        post(s, http, "/wallet/set", "{\"id\":1,\"amount\":1.2300,\"optional\":1.2300}", 200);
        s.expectRow("trailing zeros are lossless for required and optional Money", observer, 1, "1|1.23|1.23");
        var before = s.rowsNow(observer);
        post(s, http, "/wallet/set", "{\"id\":1,\"amount\":1.234}", 400);
        unchanged(s, observer, "a wider input does not bypass the target field", before);
        post(s, http, "/wallet/set", "{\"id\":1,\"amount\":2.00,\"optional\":1.234}", 400);
        unchanged(s, observer, "invalid optional input writes nothing", before);
        post(s, http, "/wallet/set", "{\"id\":1,\"amount\":3.4000e0,\"optional\":1.2300}", 200);
        s.expectRow("exponents and exact optional values", observer, 1, "1|3.40|1.23");
        post(s, http, "/wallet/negate", "{\"id\":1,\"amount\":1.2300}", 200);
        s.expectRow("negation is exact and Money does not imply non-negative", observer, 1, "1|-1.23|1.23");
        before = s.rowsNow(observer);
        post(s, http, "/wallet/create", "{\"id\":1,\"amount\":1.234}", 400);
        unchanged(s, observer, "invalid create target was never inserted", before);
        post(s, http, "/wallet/create", "{\"id\":1,\"amount\":2.3400,\"optional\":null}", 201);
        s.expectRow("valid create reaches Decimal storage", observer, 3, "3|2.34|null");
    }

    private void patches(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        patch(s, http, "{\"id\":1,\"expectedVersion\":1,\"changes\":{\"rebate\":1.2300}}", 200);
        versioned(s, observer, "absent balance is preserved", "1|5.00|1.23|2");
        patch(s, http, "{\"id\":1,\"expectedVersion\":2,\"changes\":{\"rebate\":null}}", 200);
        versioned(s, observer, "explicit optional null clears the value", "1|5.00|null|3");
        patch(s, http, "{\"id\":1,\"expectedVersion\":3,\"changes\":{\"balance\":null}}", 400);
        versioned(s, observer, "required null is not an absent patch", "1|5.00|null|3");
        patch(s, http, "{\"id\":1,\"expectedVersion\":3,\"changes\":{\"rebate\":1.234}}", 400);
        versioned(s, observer, "invalid patch precision does not advance version", "1|5.00|null|3");
        patch(s, http, "{\"id\":1,\"expectedVersion\":3,\"changes\":{\"balance\":1.2300}}", 200);
        versioned(s, observer, "valid target and version are stored together", "1|1.23|null|4");
        patch(s, http, "{\"id\":1,\"expectedVersion\":3,\"changes\":{\"balance\":2.00}}", 409);
        versioned(s, observer, "stale Money patch writes nothing", "1|1.23|null|4");
    }

    private void atomicWrites(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        s.fixtureStatement("UPDATE wallet SET balance=10.00,rebate=NULL WHERE id=1");
        post(s, http, "/wallet/credit", movement("1.2300"), 200);
        s.expectRow("atomic credit uses exact decimal addition", observer, 1, "1|11.23|null");
        post(s, http, "/wallet/debit", movement("1.23"), 200);
        s.expectRow("atomic debit uses exact decimal subtraction", observer, 1, "1|10.00|null");
        var before = s.rowsNow(observer);
        post(s, http, "/wallet/debit", movement("10000.00"), 409);
        unchanged(s, observer, "declared guard failure is a conflict", before);
        post(s, http, "/wallet/debit", movement("1.234"), 400);
        unchanged(s, observer, "delta scale is rejected before SQL can round it", before);
        var results = concurrent(http, s.baseUrl() + "/api/wallet/credit", movement("0.01"), 20);
        s.check("all concurrent exact credits succeed", results.stream().allMatch(r -> r.statusCode() == 200), statuses(results).toString());
        s.expectRow("database increments are not lost", observer, 1, "1|10.20|null");
        s.fixtureStatement("UPDATE wallet SET balance=-1.00 WHERE id=1");
        s.fixtureStatement("UPDATE wallet SET rebate=7.77 WHERE id=2");
        before = s.rowsNow(observer);
        post(s, http, "/wallet/credit", movement("0.01"), 409);
        unchanged(s, observer, "late guard failure rolls back the earlier peer write", before);
    }

    private void overflow(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        s.fixtureStatement("UPDATE wallet SET balance=" + MAX + " WHERE id=1");
        var before = s.rowsNow(observer);
        post(s, http, "/wallet/credit", movement("0.01"), 500);
        unchanged(s, observer, "strict Decimal overflow rolls back the entire transaction", before);
        s.fixtureStatement("UPDATE wallet SET balance=99999999999999999.98 WHERE id=1");
        var results = concurrent(http, s.baseUrl() + "/api/wallet/credit", movement("0.01"), 2);
        s.check("only the final in-range concurrent increment commits", statuses(results).equals(List.of(200, 500)), statuses(results).toString());
        s.expectRow("overflow never clips or overwrites the database value", observer, 1, "1|" + MAX + "|null");
    }

    private void nonStrict(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        s.reportLine("nonStrictSetup=HIKARI_CONNECTION_INIT_SQL SET SESSION sql_mode='' on every actual application connection");
        post(s, http, "/wallet/set", "{\"id\":1,\"amount\":1.2300}", 200);
        s.fixtureStatement("UPDATE wallet SET rebate=7.77 WHERE id=2");
        var before = s.rowsNow(observer);
        post(s, http, "/wallet/credit", movement("0.01"), 500);
        unchanged(s, observer, "non-strict actual write session is refused and peer mutation rolls back", before);
    }

    private static String movement(String amount) { return "{\"id\":1,\"peer\":2,\"amount\":" + amount + "}"; }

    private static void post(BusinessSliceHarness s, HttpAssertionClient http, String path, String body, int status) throws Exception {
        var response = http.postJson(s.baseUrl() + "/api" + path, Map.of(), body);
        s.record(path, response);
        s.expectStatus(path, response, status);
    }

    private static void patch(BusinessSliceHarness s, HttpAssertionClient http, String body, int status) throws Exception {
        var response = http.patchJson(s.baseUrl() + "/api/wallet/patch", Map.of(), body);
        s.record("Money PATCH", response);
        s.expectStatus("Money PATCH", response, status);
    }

    private static void unchanged(BusinessSliceHarness s, MysqlObserver observer, String label, List<String> before) throws Exception {
        var after = s.rowsNow(observer);
        s.check(label, before.equals(after), after.toString());
    }

    private static void versioned(BusinessSliceHarness s, MysqlObserver observer, String label, String row) throws Exception {
        var actual = s.rowsOf(observer, "versioned_wallet");
        s.check(label, actual.equals(List.of(row)), actual.toString());
    }

    private static List<HttpAssertionClient.Response> concurrent(HttpAssertionClient http, String url, String body, int count) throws Exception {
        List<Callable<HttpAssertionClient.Response>> tasks = java.util.stream.IntStream.range(0, count)
                .<Callable<HttpAssertionClient.Response>>mapToObj(i -> () -> http.postJson(url, Map.of(), body)).toList();
        try (var executor = Executors.newFixedThreadPool(Math.min(4, count))) {
            var results = new ArrayList<HttpAssertionClient.Response>();
            for (var task : executor.invokeAll(tasks, 60, TimeUnit.SECONDS)) results.add(task.get());
            return List.copyOf(results);
        }
    }

    private static List<Integer> statuses(List<HttpAssertionClient.Response> results) {
        return results.stream().map(HttpAssertionClient.Response::statusCode).sorted().toList();
    }
}
