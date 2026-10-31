package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Exact transport assertions shared by both new oracles, not by generated services. */
final class BoundedProblemHttp {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Projection(String table, Map<String, String> columns, Set<String> booleans) {
        Projection { columns = Map.copyOf(columns); booleans = Set.copyOf(booleans); }
    }
    final BusinessSliceHarness slice;
    final BoundedProblemFixture fixture;
    private final String tokenPrefix;
    private final HttpAssertionClient client = new HttpAssertionClient();

    BoundedProblemHttp(BusinessSliceHarness slice, BoundedProblemFixture fixture, String tokenPrefix) {
        this.slice = slice;
        this.fixture = fixture;
        this.tokenPrefix = tokenPrefix;
    }

    Map<String, String> actor(int actor) {
        return actor == 0 ? Map.of() : Map.of("Authorization", "Bearer " + tokenPrefix + String.format(Locale.ROOT, "%012d", actor));
    }
    List<Map<String, String>> invalidIdentities() {
        return List.of(Map.of(), Map.of("Authorization", "Bearer not-a-uuid"),
                Map.of("Authorization", "Bearer 99999999-9999-4999-8999-999999999999"), actor(99));
    }
    static String json(Object value) throws IOException { return JSON.writeValueAsString(value); }
    static JsonNode decode(String text) throws IOException {
        JsonNode node = JSON.readTree(text);
        if (node == null || node.isMissingNode()) throw new IOException("missing JSON document");
        return node;
    }
    JsonNode body(String label, HttpAssertionClient.Response response) {
        try { return decode(response.body()); }
        catch (IOException error) {
            slice.check(label + " strict JSON body", false, BusinessSliceHarness.describe(error));
            return MissingNode.getInstance();
        }
    }

    HttpAssertionClient.Response send(String label, String method, int actor, String path, String body) throws Exception {
        return send(label, method, actor(actor), path, body);
    }
    HttpAssertionClient.Response send(String label, String method, Map<String, String> headers, String path, String body) throws Exception {
        String url = slice.baseUrl() + path;
        var response = switch (method) {
            case "GET" -> client.get(url, headers);
            case "POST" -> client.postJson(url, headers, body);
            case "PATCH" -> client.patchJson(url, headers, body);
            default -> throw new IllegalArgumentException("unsupported oracle method " + method);
        };
        slice.record(label, response);
        return response;
    }
    HttpAssertionClient.Response read(String label, int actor, String path) throws Exception {
        var before = fixture.snapshot();
        var response = send(label, "GET", actor, path, "");
        expected(label + " GET unchanged", before, fixture.snapshot());
        return response;
    }
    void expected(String label, BoundedProblemState expected, BoundedProblemState actual) {
        slice.check(label + " exact full-database delta", expected.equals(actual),
                expected.equals(actual) ? "all tables/columns match" : "expected=" + expected + "; actual=" + actual);
    }
    void error(String label, HttpAssertionClient.Response response, int status, String code) {
        slice.expectStatus(label, response, status);
        slice.check(label + " exact error envelope", matchesError(body(label, response), code), response.body());
    }
    void invalid(String label, HttpAssertionClient.Response response, String field, String code) {
        slice.expectStatus(label, response, 400);
        var body = body(label, response);
        boolean found = field == null || body.path("fields").isArray() && StreamSupport.stream(body.path("fields").spliterator(), false)
                .anyMatch(f -> field.equals(f.path("path").asText()) && (code == null || code.equals(f.path("code").asText())));
        slice.check(label + " INVALID_REQUEST " + field + "/" + code,
                keys(body, Set.of("code", "message", "fields")) && body.path("code").asText().equals("INVALID_REQUEST")
                        && body.path("message").isTextual() && body.path("fields").isArray() && found, response.body());
    }
    void refuse(String label, String method, int actor, String path, String text, int status, String code) throws Exception {
        var before = fixture.snapshot();
        error(label, send(label, method, actor, path, text), status, code);
        expected(label + " refusal unchanged", before, fixture.snapshot());
    }
    void invalidRequest(String label, String method, int actor, String path, String text, String field, String code) throws Exception {
        var before = fixture.snapshot();
        invalid(label, send(label, method, actor, path, text), field, code);
        expected(label + " invalid unchanged", before, fixture.snapshot());
    }
    void unauthorized(String label, String method, Map<String, String> headers, String path, String text) throws Exception {
        var before = fixture.snapshot();
        var response = send(label, method, headers, path, text);
        empty(label, response, 401);
        expected(label + " anonymous unchanged", before, fixture.snapshot());
    }
    void empty(String label, HttpAssertionClient.Response response, int status) {
        slice.expectStatus(label, response, status);
        slice.check(label + " empty body", response.body().isEmpty(), response.body());
    }
    void row(String label, HttpAssertionClient.Response response, int status, Projection projection, long id) throws Exception {
        slice.expectStatus(label, response, status);
        slice.check(label + " exact complete projection", matchesRow(body(label, response), projection, fixture.snapshot(), id), response.body());
    }
    void getRow(String label, int actor, String path, Projection projection, long id) throws Exception {
        row(label, read(label, actor, path), 200, projection, id);
    }
    void page(String label, int actor, String path, Projection projection, List<Long> ids, long total, long page, long size) throws Exception {
        var response = read(label, actor, path);
        slice.expectStatus(label, response, 200);
        slice.check(label + " exact page/order/total/projections", matchesPage(body(label, response), projection, fixture.snapshot(), ids, total, page, size), response.body());
    }
    void integer(String label, int actor, String path, long expected) throws Exception {
        var response = read(label, actor, path);
        slice.expectStatus(label, response, 200);
        slice.check(label + " bare JSON integer", number(body(label, response), expected), response.body());
    }
    void badPages(String label, int actor, String path) throws Exception {
        String separator = path.contains("?") ? "&" : "?";
        for (int page : List.of(0, -1, 10001)) refuse(label + " page=" + page, "GET", actor, path + separator + "page=" + page, "", 400, "BadPage");
        for (int size : List.of(0, -1, 101)) refuse(label + " size=" + size, "GET", actor, path + separator + "size=" + size, "", 400, "BadPage");
    }

    static boolean matchesPage(JsonNode body, Projection projection, BoundedProblemState state, List<Long> ids, long total, long page, long size) {
        if (!keys(body, Set.of("total", "page", "size", "records")) || !number(body.path("total"), total)
                || !number(body.path("page"), page) || !number(body.path("size"), size)
                || !body.path("records").isArray() || body.path("records").size() != ids.size()) return false;
        for (int i = 0; i < ids.size(); i++) if (!matchesRow(body.path("records").get(i), projection, state, ids.get(i))) return false;
        return true;
    }
    static boolean matchesRow(JsonNode body, Projection projection, BoundedProblemState state, long id) {
        if (!keys(body, projection.columns().keySet()) || !state.has(projection.table(), id)) return false;
        var row = state.row(projection.table(), id);
        return projection.columns().entrySet().stream().allMatch(e -> matchesValue(body.get(e.getKey()), row.value(e.getValue()), projection.booleans().contains(e.getKey())));
    }
    private static boolean matchesValue(JsonNode node, Object expected, boolean flag) {
        if (node == null) return false;
        if (expected == null) return node.isNull();
        if (flag) return node.isBoolean() && node.booleanValue() == (((Number) expected).longValue() == 1);
        if (expected instanceof Number n) return number(node, n.longValue());
        if (!node.isTextual()) return false;
        try {
            if (expected instanceof Instant time) return Instant.parse(node.textValue()).equals(time);
            if (expected instanceof LocalDate day) return LocalDate.parse(node.textValue()).equals(day);
        } catch (java.time.format.DateTimeParseException error) { return false; }
        return expected.equals(node.textValue());
    }
    static boolean matchesError(JsonNode body, String code) {
        return keys(body, Set.of("code", "message", "fields")) && body.path("code").asText().equals(code)
                && body.path("message").asText().equals(code) && body.path("fields").isArray() && body.path("fields").isEmpty();
    }
    static boolean keys(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject()) return false;
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(keys);
    }
    static boolean number(JsonNode node, long value) { return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == value; }
    long identifier(String label, HttpAssertionClient.Response response) {
        var id = body(label, response).path("id");
        boolean valid = id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0;
        slice.check(label + " positive numeric identity", valid, id.toString());
        return valid ? id.longValue() : -1;
    }
}
