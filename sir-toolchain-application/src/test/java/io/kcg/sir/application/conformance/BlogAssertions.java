package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact JSON contracts plus before/after snapshots, not regex extraction or row-count-only checks. */
final class BlogAssertions {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PUBLIC_KEYS = Set.of("id", "author", "category", "title", "content", "likes", "createdAt");
    private static final Set<String> PRIVATE_KEYS = Set.of("id", "author", "category", "title", "content", "state", "likes", "createdAt");
    final BusinessSliceHarness slice;
    final BlogFixture fixture;
    private final HttpAssertionClient http = new HttpAssertionClient();

    BlogAssertions(BusinessSliceHarness slice, BlogFixture fixture) {
        this.slice = slice;
        this.fixture = fixture;
    }

    static Map<String, String> actor(int number) {
        if (number < 1 || number > 99) throw new IllegalArgumentException("invalid fixture actor");
        return Map.of("Authorization", "Bearer 10000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", number));
    }

    static String json(Object value) throws IOException { return JSON.writeValueAsString(value); }

    static JsonNode decode(String text) throws IOException {
        JsonNode value = JSON.readTree(text);
        if (value == null || !value.isObject()) throw new IOException("response is not one JSON object");
        return value;
    }

    JsonNode body(String label, HttpAssertionClient.Response response) {
        try {
            return decode(response.body());
        } catch (IOException error) {
            slice.check(label + " is strict JSON", false, BusinessSliceHarness.describe(error));
            return MissingNode.getInstance();
        }
    }

    HttpAssertionClient.Response post(String label, int actor, String path, String text) throws Exception {
        return post(label, actor(actor), path, text);
    }

    HttpAssertionClient.Response post(String label, Map<String, String> headers, String path, String text) throws Exception {
        var response = http.postJson(slice.baseUrl() + path, headers, text);
        slice.record(label, response);
        return response;
    }

    void unchanged(String label, BlogFixture.State before, BlogFixture.State after) {
        slice.check(label + " changes no business row in any of seven tables", before.equals(after),
                before.equals(after) ? "all seven tables unchanged" : "before=" + before + "; after=" + after);
    }

    void expected(String label, BlogFixture.State expected, BlogFixture.State actual) {
        slice.check(label + " has only the allowed full-database delta", expected.equals(actual),
                expected.equals(actual) ? "exact allowed delta" : "expected=" + expected + "; actual=" + actual);
    }

    void refusal(String label, int actor, String path, String text, int status, String code) throws Exception {
        var before = fixture.snapshot();
        var response = post(label, actor, path, text);
        error(label, response, status, code);
        unchanged(label, before, fixture.snapshot());
    }

    void error(String label, HttpAssertionClient.Response response, int status, String code) {
        slice.expectStatus(label, response, status);
        slice.check(label + " has the exact declared error envelope", matchesError(body(label, response), code), response.body());
    }

    void unauthorized(String label, Map<String, String> headers, String path, String text) throws Exception {
        var before = fixture.snapshot();
        var response = post(label, headers, path, text);
        slice.expectStatus(label, response, 401);
        slice.check(label + " has an empty authentication response", response.body().isEmpty(), response.body());
        unchanged(label, before, fixture.snapshot());
    }

    void emptyCreated(String label, HttpAssertionClient.Response response) {
        slice.expectStatus(label, response, 201);
        slice.check(label + " has an empty body", response.body().isEmpty(), response.body());
    }

    void postView(String label, HttpAssertionClient.Response response, long id, boolean publicView,
                  BlogFixture.State state, Duration tolerance, Long dynamicLikes) {
        slice.check(label + " matches all JSON fields, nested identities and JDBC values",
                matchesPost(body(label, response), state, id, publicView, tolerance, dynamicLikes), response.body());
    }

    void detail(String label, long id) throws Exception {
        var before = fixture.snapshot();
        var response = http.get(slice.baseUrl() + "/api/posts/" + id, Map.of());
        slice.record(label, response);
        slice.expectStatus(label, response, 200);
        var after = fixture.snapshot();
        postView(label, response, id, true, after, Duration.ZERO, null);
        unchanged(label, before, after);
    }

    void missingDetail(String label, long id) throws Exception {
        var before = fixture.snapshot();
        var response = http.get(slice.baseUrl() + "/api/posts/" + id, Map.of());
        slice.record(label, response);
        error(label, response, 404, "PostNotFound");
        unchanged(label, before, fixture.snapshot());
    }

    void page(String label, String path, List<Long> ids, long total, long page, long size) throws Exception {
        var before = fixture.snapshot();
        var response = http.get(slice.baseUrl() + path, Map.of());
        slice.record(label, response);
        slice.expectStatus(label, response, 200);
        var after = fixture.snapshot();
        slice.check(label + " has exact page keys, totals, ordering and full nested records",
                matchesPage(body(label, response), after, ids, total, page, size), response.body());
        unchanged(label, before, after);
    }

    void missingTag(String label) throws Exception {
        var before = fixture.snapshot();
        var response = http.get(slice.baseUrl() + "/api/tags/999999/posts?page=1&size=10", Map.of());
        slice.record(label, response);
        error(label, response, 404, "TagNotFound");
        unchanged(label, before, fixture.snapshot());
    }

    void badPage(String label, String path) throws Exception {
        var before = fixture.snapshot();
        var response = http.get(slice.baseUrl() + path, Map.of());
        slice.record(label, response);
        error(label, response, 400, "BadPage");
        unchanged(label, before, fixture.snapshot());
    }

    static boolean matchesPage(JsonNode body, BlogFixture.State state, List<Long> ids, long total, long page, long size) {
        if (!keys(body, Set.of("total", "page", "size", "records")) || !number(body.path("total"), total)
                || !number(body.path("page"), page) || !number(body.path("size"), size)
                || !body.path("records").isArray() || body.path("records").size() != ids.size()) return false;
        for (int i = 0; i < ids.size(); i++) {
            if (!matchesPost(body.path("records").get(i), state, ids.get(i), true, Duration.ZERO, null)) return false;
        }
        return true;
    }

    static boolean matchesPost(JsonNode body, BlogFixture.State state, long id, boolean publicView,
                               Duration tolerance, Long dynamicLikes) {
        if (!keys(body, publicView ? PUBLIC_KEYS : PRIVATE_KEYS) || !state.has("post", id)) return false;
        var row = state.row("post", id);
        if (!number(body.path("id"), id) || !number(body.path("likes"), dynamicLikes == null ? row.number("likes") : dynamicLikes)
                || !text(body.path("title"), row.text("title")) || !text(body.path("content"), row.text("content"))
                || !moment(body.path("createdAt"), row.time("created_at"), tolerance)) return false;
        if (!publicView) return number(body.path("author"), row.number("author_id"))
                && number(body.path("category"), row.number("category_id")) && text(body.path("state"), row.text("state"));
        return nested(body.path("author"), state, "user", row.number("author_id"))
                && nested(body.path("category"), state, "category", row.number("category_id"));
    }

    private static boolean nested(JsonNode body, BlogFixture.State state, String table, long id) {
        return keys(body, Set.of("id", "name")) && state.has(table, id)
                && number(body.path("id"), id) && text(body.path("name"), state.row(table, id).text("name"));
    }

    static boolean matchesError(JsonNode body, String code) {
        return keys(body, Set.of("code", "message", "fields")) && text(body.path("code"), code)
                && text(body.path("message"), code) && body.path("fields").isArray() && body.path("fields").isEmpty();
    }

    static boolean keys(JsonNode body, Set<String> expected) {
        if (body == null || !body.isObject()) return false;
        var actual = new HashSet<String>();
        body.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    static boolean number(JsonNode node, long expected) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() == expected;
    }

    static long identifier(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : -1;
    }

    private static boolean text(JsonNode node, String expected) { return node.isTextual() && node.textValue().equals(expected); }

    private static boolean moment(JsonNode node, Instant expected, Duration tolerance) {
        if (!node.isTextual() || expected == null) return false;
        try {
            return Duration.between(expected, Instant.parse(node.textValue())).abs().compareTo(tolerance) <= 0;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
