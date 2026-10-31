package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Exact JSON, scalar-body and full-database comparisons; a field merely being present is never a pass. */
final class EventsAssertions {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> EVENT_KEYS = Set.of("id", "organizer", "title", "startsAt", "capacity", "registered", "remaining", "fee");
    static final List<Map<String, String>> INVALID_IDENTITIES = List.of(Map.of(),
            Map.of("Authorization", "Bearer not-a-uuid"),
            Map.of("Authorization", "Bearer 99999999-9999-4999-8999-999999999999"), actor(99));
    final BusinessSliceHarness slice;
    final EventsFixture fixture;
    private final HttpAssertionClient http = new HttpAssertionClient();

    /** A read plus the database state observed after it; the state before it was already compared. */
    record Read(HttpAssertionClient.Response response, EventsFixture.State state) { }

    EventsAssertions(BusinessSliceHarness slice, EventsFixture fixture) {
        this.slice = slice;
        this.fixture = fixture;
    }

    static Map<String, String> actor(int number) {
        if (number < 1 || number > 99) throw new IllegalArgumentException("invalid fixture actor");
        return Map.of("Authorization", "Bearer 30000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", number));
    }

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

    HttpAssertionClient.Response post(String label, Map<String, String> headers, String path, String text) throws Exception {
        var response = http.postJson(slice.baseUrl() + path, headers, text);
        slice.record(label, response);
        return response;
    }

    /** A GET that must not write: compares all four tables before and after. */
    Read read(String label, Map<String, String> headers, String path) throws Exception {
        var before = fixture.snapshot();
        var response = http.get(slice.baseUrl() + path, headers);
        slice.record(label, response);
        var after = fixture.snapshot();
        unchanged(label, before, after);
        return new Read(response, after);
    }

    void unchanged(String label, EventsFixture.State before, EventsFixture.State after) {
        slice.check(label + " changes no business row in any of four tables", before.equals(after),
                before.equals(after) ? "all four tables unchanged" : "before=" + before + "; after=" + after);
    }

    void expected(String label, EventsFixture.State expected, EventsFixture.State actual) {
        slice.check(label + " has only the allowed full-database delta", expected.equals(actual),
                expected.equals(actual) ? "exact allowed delta" : "expected=" + expected + "; actual=" + actual);
    }

    void error(String label, HttpAssertionClient.Response response, int status, String code) {
        slice.expectStatus(label, response, status);
        slice.check(label + " has the exact declared error envelope", matchesError(body(label, response), code), response.body());
    }

    void refusedRead(String label, Map<String, String> headers, String path, int status, String code) throws Exception {
        error(label, read(label, headers, path).response(), status, code);
    }

    void unauthorized(String label, boolean post, Map<String, String> headers, String path, String text) throws Exception {
        var before = fixture.snapshot();
        var response = post ? post(label, headers, path, text) : http.get(slice.baseUrl() + path, headers);
        if (!post) slice.record(label, response);
        slice.expectStatus(label, response, 401);
        slice.check(label + " has an empty authentication response", response.body().isEmpty(), response.body());
        unchanged(label, before, fixture.snapshot());
    }

    /** 400 INVALID_REQUEST naming the field; {@code code} is checked only when the spec fixes it. */
    void invalid(String label, HttpAssertionClient.Response response, String path, String code) {
        slice.expectStatus(label, response, 400);
        var body = body(label, response);
        boolean named = path == null || body.path("fields").isArray() && StreamSupport.stream(body.path("fields").spliterator(), false)
                .anyMatch(f -> path.equals(f.path("path").asText()) && (code == null || code.equals(f.path("code").asText())));
        slice.check(label + " is INVALID_REQUEST naming " + path + " (code " + code + ")",
                keys(body, Set.of("code", "message", "fields")) && "INVALID_REQUEST".equals(body.path("code").asText())
                        && body.path("message").isTextual() && named, response.body());
        if (path != null) slice.reportLine("observedFieldErrors[" + label + "]=" + body.path("fields"));
    }

    void page(String label, Map<String, String> headers, String path, boolean events, List<Long> ids,
              long total, long page, long size) throws Exception {
        var read = read(label, headers, path);
        slice.expectStatus(label, read.response(), 200);
        slice.check(label + " has exact page keys, totals, ordering and full records",
                matchesPage(body(label, read.response()), read.state(), events, ids, total, page, size), read.response().body());
    }

    void badPage(String label, Map<String, String> headers, String path) throws Exception {
        refusedRead(label, headers, path, 400, "BadPage");
    }

    void integer(String label, Map<String, String> headers, String path, long expected) throws Exception {
        var response = read(label, headers, path).response();
        slice.expectStatus(label, response, 200);
        String text = response.body().trim();
        slice.check(label + " is the bare JSON integer " + expected, text.matches("-?[0-9]+") && Long.parseLong(text) == expected, response.body());
    }

    void decimal(String label, Map<String, String> headers, String path, String expected) throws Exception {
        var response = read(label, headers, path).response();
        slice.expectStatus(label, response, 200);
        String text = response.body().trim();
        boolean matches = text.matches("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
                && new BigDecimal(text).compareTo(new BigDecimal(expected)) == 0;
        slice.check(label + " is the bare JSON number " + expected, matches, response.body());
    }

    static boolean matchesPage(JsonNode body, EventsFixture.State state, boolean events, List<Long> ids,
                               long total, long page, long size) {
        if (!keys(body, Set.of("total", "page", "size", "records")) || !number(body.path("total"), total)
                || !number(body.path("page"), page) || !number(body.path("size"), size)
                || !body.path("records").isArray() || body.path("records").size() != ids.size()) return false;
        for (int i = 0; i < ids.size(); i++) {
            var record = body.path("records").get(i);
            if (events ? !matchesEvent(record, state, ids.get(i), Duration.ZERO) : !matchesRegistration(record, state, ids.get(i), Duration.ZERO)) return false;
        }
        return true;
    }

    static boolean matchesEvent(JsonNode body, EventsFixture.State state, long id, Duration tolerance) {
        if (!keys(body, EVENT_KEYS) || !state.has("event", id)) return false;
        var row = state.row("event", id);
        return number(body.path("id"), id) && number(body.path("organizer"), row.number("organizer_id"))
                && text(body.path("title"), row.text("title")) && moment(body.path("startsAt"), row.time("starts_at"), tolerance)
                && number(body.path("capacity"), row.number("capacity")) && number(body.path("registered"), row.number("registered"))
                && number(body.path("remaining"), row.number("capacity") - row.number("registered"))
                && body.path("fee").isNumber() && body.path("fee").decimalValue().compareTo(row.decimal("fee")) == 0;
    }

    static boolean matchesRegistration(JsonNode body, EventsFixture.State state, long id, Duration tolerance) {
        if (!keys(body, Set.of("id", "event", "attendee", "fee", "registeredAt")) || !state.has("registration", id)) return false;
        var row = state.row("registration", id);
        return number(body.path("id"), id) && number(body.path("event"), row.number("event_id"))
                && number(body.path("attendee"), row.number("attendee_id"))
                && body.path("fee").isNumber() && body.path("fee").decimalValue().compareTo(row.decimal("fee")) == 0
                && moment(body.path("registeredAt"), row.time("registered_at"), tolerance);
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
