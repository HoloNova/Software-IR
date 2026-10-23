package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Real generated HTTP and independent JDBC: exact totals, rollback and quantity-based stock concurrency. */
class MultiplicationBusinessConformanceIT {
    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "G6 exact multiplication and multi-unit stock deltas", "/valid/multiplication.sir",
            "/conformance/mysql/multiplication-ddl.sql", "/conformance/mysql/multiplication-seed.sql",
            "product", "/api/products/show?id=1", "multiplication-report.txt", "multiplication", "kcg.multiplication-conformance.enabled");

    @Test void multiplicationAgainstRealMysql() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[MULTIPLICATION-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        try { slice.verify(() -> execute(slice)); }
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
        s.setHttpStatus("READY (multiplication product query)");
        try (var observer = s.openObserver(url)) {
            s.setRowsBefore(s.rowsNow(observer));
            var http = new HttpAssertionClient();
            ordinaryAndInvalidQuantities(s, observer, http);
            finalPrecisionAndRollback(s, observer, http);
            integerOverflow(s, observer, http);
            concurrentPurchases(s, observer, http);
            verifyLinesAndTotals(s, observer);
            computedResponses(s, observer, http);
            courseCapacityCounters(s, observer, http);
            IntervalBookingChecks.verify(s, observer, http);
            s.setRowsAfter(s.rowsNow(observer));
        }
    }

    private void ordinaryAndInvalidQuantities(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var created = post(s, http, purchase(1, "3"), 201);
        expectDecimal(s, "computed create response doubles the exact candidate amount", created, "doubledTotal", "7.38");
        s.expectRow("three units decrement the same quantity", observer, 1, "1|1.2300|17");
        s.check("three units persist exact Money total", receipts(s, observer).stream().anyMatch(row -> row.endsWith("|1|3.69|3")), receipts(s, observer).toString());
        for (String quantity : List.of("missing", "null", "0", "-1", "2147483648")) {
            var before = state(s, observer);
            post(s, http, quantity.equals("missing") ? "{\"id\":1}" : purchase(1, quantity), 400);
            s.check("invalid quantity " + quantity + " writes no stock or receipt",
                    before.equals(state(s, observer)), state(s, observer).toString());
        }
        var stockBefore = s.rowsNow(observer);
        post(s, http, purchase(1, "18"), 409);
        s.check("insufficient stock follows the declared conflict", stockBefore.equals(s.rowsNow(observer)), s.rowsNow(observer).toString());
    }

    private void finalPrecisionAndRollback(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        for (int product : List.of(2, 3)) {
            var before = state(s, observer);
            post(s, http, purchase(product, product == 2 ? "3" : "2"), 400);
            s.check("inexact or overflowing total rolls back an earlier stock statement for " + product,
                    before.equals(state(s, observer)), state(s, observer).toString());
        }
        post(s, http, purchase(2, "8"), 201);
        s.expectRow("a four-decimal price is checked only after the exact product", observer, 2, "2|0.1250|2");
        s.check("0.1250 times 8 stores precisely 1.00", receipts(s, observer).stream().anyMatch(row -> row.endsWith("|2|1.00|8")), receipts(s, observer).toString());
    }

    private void integerOverflow(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var response = http.postJson(s.baseUrl() + "/api/multiply", Map.of(),
                "{\"price\":1.00,\"quantity\":1,\"left\":50000,\"right\":50000,\"wide\":1}");
        s.check("integer overflow is an execution failure, not a wrapped value", response.statusCode() == 500, Integer.toString(response.statusCode()));
        s.check("overflow never inserts its calculation", s.rowsOf(observer, "calculation").isEmpty(), "calculation rows checked independently");
    }

    private void concurrentPurchases(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        s.fixtureStatement("UPDATE product SET stock=20 WHERE id=1");
        int before = receipts(s, observer).size();
        var tasks = new ArrayList<Callable<HttpAssertionClient.Response>>();
        for (int index = 0; index < 12; index++) tasks.add(() -> http.postJson(s.baseUrl() + "/api/purchase", Map.of(), purchase(1, "3")));
        List<HttpAssertionClient.Response> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var future : pool.invokeAll(tasks)) results.add(future.get(30, TimeUnit.SECONDS));
        }
        long successes = results.stream().filter(response -> response.statusCode() == 201).count();
        s.check("only six concurrent purchases of three units fit twenty stock", successes == 6
                && results.stream().allMatch(response -> response.statusCode() == 201 || response.statusCode() == 409),
                results.stream().map(HttpAssertionClient.Response::statusCode).toList().toString());
        s.expectRow("concurrent stock deductions are never lost or oversold", observer, 1, "1|1.2300|2");
        var rows = receipts(s, observer);
        s.check("only successful orders commit", rows.size() == before + successes, rows.toString());
        s.check("every concurrent order retains its own exact total and quantity", rows.subList(before, rows.size()).stream()
                .allMatch(row -> row.endsWith("|1|3.69|3")), rows.toString());
    }

    private void verifyLinesAndTotals(BusinessSliceHarness s, MysqlObserver observer) throws Exception {
        var receipts = receipts(s, observer).stream().map(row -> row.split("\\|"))
                .collect(Collectors.toMap(row -> row[0], row -> row));
        var lines = s.rowsOf(observer, "receipt_line").stream().map(row -> row.split("\\|")).toList();
        boolean consistent = lines.size() == receipts.size()
                && lines.stream().map(line -> line[1]).distinct().count() == receipts.size()
                && lines.stream().allMatch(line -> {
                    var receipt = receipts.get(line[1]);
                    return receipt != null && line[2].equals(receipt[3])
                            && new BigDecimal(line[3]).multiply(new BigDecimal(line[2])).compareTo(new BigDecimal(receipt[2])) == 0;
                });
        s.check("each line references its persisted receipt and preserves exact quantity and price", consistent,
                s.rowsOf(observer, "receipt_line").toString());
    }

    private static void computedResponses(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var before = state(s, observer);
        var exact = http.get(s.baseUrl() + "/api/products/show?id=4", Map.of());
        if (s.expectStatus("computed four-decimal display is served", exact, 200)) {
            expectDecimal(s, "Money view computation stays exact without rounding to storage scale", exact, "inventoryValue", "0.375");
            s.expectField("the same row has stock", exact, "soldOut", "false");
        }
        var empty = http.get(s.baseUrl() + "/api/products/show?id=5", Map.of());
        if (s.expectStatus("computed zero-stock display is served", empty, 200)) {
            expectDecimal(s, "zero is a computed value", empty, "inventoryValue", "0");
            s.expectField("the row-local zero-stock comparison is true", empty, "soldOut", "true");
        }
        var concurrent = http.get(s.baseUrl() + "/api/products/show?id=1", Map.of());
        if (s.expectStatus("computed display after concurrent purchases is served", concurrent, 200)) {
            expectDecimal(s, "read projection uses current persisted stock", concurrent, "inventoryValue", "2.46");
            s.expectFields("concurrent stock remains a source field", concurrent, Map.of("stock", "2", "soldOut", "false"));
        }
        s.check("response computations never persist their values or modify source rows", before.equals(state(s, observer)),
                state(s, observer).toString());
    }

    private static void courseCapacityCounters(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var tasks = new ArrayList<Callable<HttpAssertionClient.Response>>();
        for (int index = 0; index < 8; index++) tasks.add(() -> http.postJson(s.baseUrl() + "/api/courses/enroll", Map.of(), "{\"id\":1}"));
        List<HttpAssertionClient.Response> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var future : pool.invokeAll(tasks)) results.add(future.get(30, TimeUnit.SECONDS));
        }
        long enrolled = results.stream().filter(response -> response.statusCode() >= 200 && response.statusCode() < 300).count();
        s.check("the atomic guard admits exactly the remaining seats, not the displayed value", enrolled == 3
                && results.stream().allMatch(response -> response.statusCode() < 300 || response.statusCode() == 409),
                results.stream().map(HttpAssertionClient.Response::statusCode).toList().toString());
        expectCounters(s, observer, "enrollment is one atomic counter, never a separate stored remaining column", "1|3|3");
        var full = http.get(s.baseUrl() + "/api/courses/show?id=1", Map.of());
        if (s.expectStatus("a committed full course is served", full, 200)) {
            s.expectFields("the response derives zero remaining and full from the same committed row", full,
                    Map.of("capacity", "3", "enrolled", "3", "remaining", "0", "full", "true"));
        }
        var alreadyFull = http.get(s.baseUrl() + "/api/courses/show?id=2", Map.of());
        if (s.expectStatus("a pre-filled course is served", alreadyFull, 200)) {
            s.expectFields("a pre-filled course also derives an empty remainder", alreadyFull,
                    Map.of("enrolled", "1", "remaining", "0", "full", "true"));
        }
        var rejected = http.postJson(s.baseUrl() + "/api/courses/enroll", Map.of(), "{\"id\":1}");
        s.check("a full course rejects further enrollment with the declared conflict", rejected.statusCode() == 409,
                Integer.toString(rejected.statusCode()));
        expectCounters(s, observer, "a rejected enrollment leaves the counter untouched", "1|3|3");
    }

    private static void expectCounters(BusinessSliceHarness s, MysqlObserver observer, String label, String expected) throws Exception {
        var rows = s.rowsOf(observer, "course");
        var actual = rows.stream().filter(row -> row.startsWith("1|")).toList();
        s.check(label, actual.equals(List.of(expected)), "course=" + rows);
    }

    private static void expectDecimal(BusinessSliceHarness s, String label, HttpAssertionClient.Response response,
            String field, String expected) {
        String actual = BusinessSliceHarness.field(response.body(), field);
        boolean matches;
        try { matches = new BigDecimal(actual).compareTo(new BigDecimal(expected)) == 0; }
        catch (NumberFormatException invalid) { matches = false; }
        s.check(label, matches, "expected=" + expected + " actual=" + actual);
    }

    private static List<List<String>> state(BusinessSliceHarness s, MysqlObserver observer) throws Exception {
        return List.of(s.rowsNow(observer), receipts(s, observer), s.rowsOf(observer, "receipt_line"));
    }

    private static String purchase(int id, String quantity) { return "{\"id\":" + id + ",\"quantity\":" + quantity + "}"; }
    private static HttpAssertionClient.Response post(BusinessSliceHarness s, HttpAssertionClient http, String body, int expected) throws Exception {
        var response = http.postJson(s.baseUrl() + "/api/purchase", Map.of(), body);
        s.check("purchase " + body + " returns " + expected, response.statusCode() == expected, Integer.toString(response.statusCode()));
        return response;
    }
    private static List<String> receipts(BusinessSliceHarness s, MysqlObserver observer) throws Exception {
        return s.rowsOf(observer, "receipt");
    }
}
