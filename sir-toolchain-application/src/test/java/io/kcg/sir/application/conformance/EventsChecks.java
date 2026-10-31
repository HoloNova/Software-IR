package io.kcg.sir.application.conformance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Spec B01–B02, C01–C04, L01–L03, M01–M02, R01–R13, V01–V05, X01–X02 and I01. Reads compare all four tables before
 * and after; every successful write compares the full-database delta; counters are checked against stored rows.
 */
final class EventsChecks {
    private record Endpoint(boolean post, String path, String body) { }
    private EventsChecks() { }

    private static final List<Long> UPCOMING = List.of(10L, 11L, 12L, 14L, 15L, 16L, 17L, 18L);

    // ---------------------------------------------------------------- creation and identity

    static void identityAndCreation(EventsAssertions a) throws Exception {
        a.page("B01 anonymous readiness", Map.of(), "/api/events?page=1&size=10", true, UPCOMING, 8, 1, 10);
        var endpoints = List.of(
                new Endpoint(true, "/api/events", "{\"title\":\"auth\",\"startsAt\":\"2099-08-01T09:00:00Z\",\"capacity\":5,\"fee\":1}"),
                new Endpoint(true, "/api/events/10/registrations", "{}"),
                new Endpoint(false, "/api/my/registrations?page=1&size=10", ""),
                new Endpoint(false, "/api/events/16/revenue", ""),
                new Endpoint(false, "/api/events/16/registration-count", ""));
        for (Endpoint endpoint : endpoints) {
            for (int index = 0; index < EventsAssertions.INVALID_IDENTITIES.size(); index++) {
                a.unauthorized("B02 " + endpoint.path() + " invalid identity " + index, endpoint.post(),
                        EventsAssertions.INVALID_IDENTITIES.get(index), endpoint.path(), endpoint.body());
            }
        }
        create(a, "C01 organizer creates", 1, eventBody("\"New Fest\"", "\"2099-08-01T09:00:00Z\"", "50", "12.50"), 50, "12.50");
        create(a, "C02 free past event, identity from token", 2, eventBody("\"Free Fest\"", "\"2000-01-01T00:00:00Z\"", "1", "0"), 1, "0");
        create(a, "C02 largest fee", 1, eventBody("\"Big\"", "\"2099-08-01T09:00:00Z\"", "5", "99999999.99"), 5, "99999999.99");
        create(a, "C02 equal-valued fee spelling", 1, eventBody("\"Equal\"", "\"2099-08-01T09:00:00Z\"", "5", "12.3400"), 5, "12.34");
        create(a, "C02 100-character title", 1, eventBody("\"" + "t".repeat(100) + "\"", "\"2099-08-01T09:00:00Z\"", "5", "1"), 5, "1");
        invalidCreation(a);
    }

    /** A null literal means the member is omitted; the string "null" is sent as JSON null. */
    private static String eventBody(String title, String startsAt, String capacity, String fee) {
        var parts = new java.util.ArrayList<String>();
        if (title != null) parts.add("\"title\":" + title);
        if (startsAt != null) parts.add("\"startsAt\":" + startsAt);
        if (capacity != null) parts.add("\"capacity\":" + capacity);
        if (fee != null) parts.add("\"fee\":" + fee);
        return "{" + String.join(",", parts) + "}";
    }

    private static void create(EventsAssertions a, String label, int actor, String body, long capacity, String fee) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.post(label, EventsAssertions.actor(actor), "/api/events", body);
        a.slice.expectStatus(label, response, 201);
        long id = EventsAssertions.identifier(a.body(label, response).path("id"));
        var after = a.fixture.snapshot();
        var added = after.addedSince("event", before);
        boolean one = id > 0 && added.size() == 1 && added.getFirst().number("id") == id;
        a.slice.check(label + " returns the one newly inserted numeric id", one, "id=" + id + "; inserted=" + added.size());
        if (!one) return;
        var row = added.getFirst();
        a.slice.check(label + " stores the token's organizer, a zero counter and the exact fee",
                row.number("organizer_id") == actor && row.number("registered") == 0 && row.number("capacity") == capacity
                        && row.decimal("fee").compareTo(new BigDecimal(fee)) == 0, row.toString());
        a.expected(label, before.add("event", row), after);
        a.slice.check(label + " matches all JSON fields and JDBC values",
                EventsAssertions.matchesEvent(a.body(label, response), after, id, Duration.ZERO), response.body());
    }

    private static void invalidCreation(EventsAssertions a) throws Exception {
        String t = "\"x\"", s = "\"2099-08-01T09:00:00Z\"";
        var cases = new String[][] {
                {"fee 100000000.00", eventBody(t, s, "5", "100000000.00"), "fee", null}, {"fee 12.345", eventBody(t, s, "5", "12.345"), "fee", null},
                {"fee -0.01", eventBody(t, s, "5", "-0.01"), "fee", null}, {"fee omitted", eventBody(t, s, "5", null), "fee", "NotNull"},
                {"fee null", eventBody(t, s, "5", "null"), "fee", "NotNull"},
                {"capacity 0", eventBody(t, s, "0", "1"), "capacity", null}, {"capacity -1", eventBody(t, s, "-1", "1"), "capacity", null},
                {"capacity omitted", eventBody(t, s, null, "1"), "capacity", "NotNull"}, {"capacity null", eventBody(t, s, "null", "1"), "capacity", "NotNull"},
                {"title omitted", eventBody(null, s, "5", "1"), "title", "NotBlank"}, {"title null", eventBody("null", s, "5", "1"), "title", "NotBlank"},
                {"title empty", eventBody("\"\"", s, "5", "1"), "title", "NotBlank"}, {"title blank", eventBody("\"   \"", s, "5", "1"), "title", "NotBlank"},
                {"title 101", eventBody("\"" + "a".repeat(101) + "\"", s, "5", "1"), "title", null},
                {"startsAt omitted", eventBody(t, null, "5", "1"), "startsAt", "NotNull"}, {"startsAt null", eventBody(t, "null", "5", "1"), "startsAt", "NotNull"},
                {"startsAt text", eventBody(t, "\"tomorrow\"", "5", "1"), null, null}};
        for (String[] c : cases) {
            String label = "C03 " + c[0];
            var before = a.fixture.snapshot();
            a.invalid(label, a.post(label, EventsAssertions.actor(1), "/api/events", c[1]), c[2], c[3]);
            a.unchanged(label, before, a.fixture.snapshot());
        }
        for (String extra : List.of("\"organizer\":2", "\"registered\":5")) {
            String label = "C04 forged " + extra;
            var before = a.fixture.snapshot();
            String body = eventBody(t, s, "5", "1");
            a.invalid(label, a.post(label, EventsAssertions.actor(1), "/api/events", body.substring(0, body.length() - 1) + "," + extra + "}"), null, null);
            a.unchanged(label, before, a.fixture.snapshot());
        }
    }

    // ---------------------------------------------------------------- listing and own registrations

    static void listing(EventsAssertions a) throws Exception {
        a.page("L01 size 3 page 1", Map.of(), "/api/events?size=3", true, List.of(10L, 11L, 12L), 8, 1, 3);
        a.page("L01 size 3 page 2", Map.of(), "/api/events?size=3&page=2", true, List.of(14L, 15L, 16L), 8, 2, 3);
        a.page("L01 size 3 page 3", Map.of(), "/api/events?size=3&page=3", true, List.of(17L, 18L), 8, 3, 3);
        a.page("L01 size 3 page 4", Map.of(), "/api/events?size=3&page=4", true, List.of(), 8, 4, 3);
        a.page("L02 defaults", Map.of(), "/api/events", true, UPCOMING, 8, 1, 20);
        a.page("L02 page default alone", Map.of(), "/api/events?size=2", true, List.of(10L, 11L), 8, 1, 2);
        a.page("L02 size default alone", Map.of(), "/api/events?page=2", true, List.of(), 8, 2, 20);
        a.page("L02 inclusive upper bounds", Map.of(), "/api/events?page=10000&size=100", true, List.of(), 8, 10000, 100);
        badPages(a, Map.of(), "L03", "/api/events?");
        badPages(a, EventsAssertions.actor(3), "L03 mine", "/api/my/registrations?");
        var expected = Map.of(3, List.of(6L, 3L), 4, List.of(4L, 1L), 5, List.of(5L), 6, List.of(2L), 7, List.of(7L),
                1, List.<Long>of(), 2, List.<Long>of(), 10, List.<Long>of());
        for (var entry : expected.entrySet()) {
            a.page("M01 registrations of user " + entry.getKey(), EventsAssertions.actor(entry.getKey()), "/api/my/registrations",
                    false, entry.getValue(), entry.getValue().size(), 1, 20);
        }
        var carol = EventsAssertions.actor(3);
        a.page("M02 carol size 1 page 1", carol, "/api/my/registrations?size=1", false, List.of(6L), 2, 1, 1);
        a.page("M02 carol size 1 page 2", carol, "/api/my/registrations?size=1&page=2", false, List.of(3L), 2, 2, 1);
        a.page("M02 carol size 1 page 3", carol, "/api/my/registrations?size=1&page=3", false, List.of(), 2, 3, 1);
    }

    private static void badPages(EventsAssertions a, Map<String, String> headers, String id, String prefix) throws Exception {
        for (int page : List.of(0, -1, 10001)) a.badPage(id + " rejected page " + page, headers, prefix + "page=" + page + "&size=2");
        for (int size : List.of(0, -1, 101)) a.badPage(id + " rejected size " + size, headers, prefix + "page=1&size=" + size);
    }

    // ---------------------------------------------------------------- revenue and headcount on seeded data

    static void revenue(EventsAssertions a) throws Exception {
        var alice = EventsAssertions.actor(1);
        var bob = EventsAssertions.actor(2);
        stats(a, "V01 event 16", alice, 16, "57.50", 3);
        stats(a, "V02 free event 11", alice, 11, "0", 1);
        stats(a, "V02 started event 13", alice, 13, "5.00", 1);
        stats(a, "V02 dime event 18", alice, 18, "0.30", 3);
        for (long event : List.of(10L, 14L, 15L)) stats(a, "V02 empty event " + event, alice, event, "0", 0);
        stats(a, "V02 event 12", bob, 12, "15.50", 1);
        stats(a, "V02 empty event 17", bob, 17, "0", 0);
        for (var c : new Object[][] {{2, 16}, {1, 12}, {3, 11}}) {
            for (String leaf : List.of("revenue", "registration-count")) {
                a.refusedRead("V03 user " + c[0] + " on " + c[1] + " " + leaf, EventsAssertions.actor((Integer) c[0]), "/api/events/" + c[1] + "/" + leaf, 403, "Forbidden");
            }
        }
        for (String leaf : List.of("revenue", "registration-count")) {
            a.refusedRead("V04 unknown event " + leaf, alice, "/api/events/999999/" + leaf, 404, "EventNotFound");
        }
    }

    private static void stats(EventsAssertions a, String label, Map<String, String> who, long event, String revenue, long count) throws Exception {
        a.decimal(label + " revenue", who, "/api/events/" + event + "/revenue", revenue);
        a.integer(label + " registration count", who, "/api/events/" + event + "/registration-count", count);
    }

    // ---------------------------------------------------------------- registering

    private static Created register(EventsAssertions a, String label, int actor, long event, String body, String fee) throws Exception {
        var before = a.fixture.snapshot();
        Instant started = Instant.now();
        var response = a.post(label, EventsAssertions.actor(actor), "/api/events/" + event + "/registrations", body);
        Instant finished = Instant.now();
        a.slice.expectStatus(label, response, 201);
        long id = EventsAssertions.identifier(a.body(label, response).path("id"));
        var after = a.fixture.snapshot();
        var added = after.addedSince("registration", before);
        boolean one = id > 0 && added.size() == 1 && added.getFirst().number("id") == id;
        a.slice.check(label + " returns the one newly inserted numeric id", one, "id=" + id + "; inserted=" + added.size());
        if (!one) return null;
        var row = added.getFirst();
        a.slice.check(label + " stores the token's attendee, the event and the fee at that moment and the request time",
                row.number("event_id") == event && row.number("attendee_id") == actor
                        && row.decimal("fee").compareTo(new BigDecimal(fee)) == 0
                        && !row.time("registered_at").isBefore(started.minusMillis(1))
                        && !row.time("registered_at").isAfter(finished.plusMillis(1)), row + "; request=" + started + ".." + finished);
        long counter = before.row("event", event).number("registered") + 1;
        a.expected(label, before.add("registration", row).withEvent(event, "registered", counter), after);
        a.slice.check(label + " matches all JSON fields and JDBC values",
                EventsAssertions.matchesRegistration(a.body(label, response), after, id, Duration.ofMillis(1)), response.body());
        return new Created(row);
    }

    private record Created(EventsFixture.Row row) { }

    private static void refuse(EventsAssertions a, String label, int actor, long event, int status, String code) throws Exception {
        var before = a.fixture.snapshot();
        a.error(label, a.post(label, EventsAssertions.actor(actor), "/api/events/" + event + "/registrations", "{}"), status, code);
        a.unchanged(label, before, a.fixture.snapshot());
    }

    static void registration(EventsAssertions a) throws Exception {
        var mine = new java.util.ArrayList<Long>();
        var first = register(a, "R01 first registration", 1, 10, "{}", "20.00");
        if (first == null) return;
        mine.add(first.row().number("id"));
        refuse(a, "R02 repeat", 1, 10, 409, "AlreadyRegistered");
        register(a, "R03 second attendee", 2, 10, "{}", "20.00");
        register(a, "R03 third attendee fills the event", 3, 10, "{}", "20.00");
        stats(a, "V05 revenue after three registrations", EventsAssertions.actor(1), 10, "60.00", 3);
        refuse(a, "R04 event is full", 4, 10, 409, "EventFull");
        refuse(a, "R05 already registered is reported before full", 1, 10, 409, "AlreadyRegistered");
        refuse(a, "R06 started event", 10, 13, 409, "RegistrationClosed");
        refuse(a, "R06 started event is reported before already registered", 6, 13, 409, "RegistrationClosed");
        refuse(a, "R07 unknown event", 1, 999999, 404, "EventNotFound");
        var free = register(a, "R08 free event", 1, 11, "{}", "0.00");
        if (free != null) mine.add(free.row().number("id"));
        register(a, "R09 URL id overrides the body id", 2, 11, "{\"id\":10}", "0.00");
        var own = register(a, "R10 organizer registers for own event", 1, 14, "{}", "10.00");
        if (own != null) mine.add(own.row().number("id"));
        snapshotFee(a);
        var state = a.fixture.snapshot();
        a.page("R12 full event stays listed", Map.of(), "/api/events?page=1&size=20", true, UPCOMING, 8, 1, 20);
        a.slice.check("R12 event 10 is full yet still listed",
                state.row("event", 10).number("registered") == state.row("event", 10).number("capacity"), state.row("event", 10).toString());
        var order = state.table("registration").stream().filter(r -> r.number("attendee_id") == 1)
                .sorted(Comparator.comparing((EventsFixture.Row r) -> r.time("registered_at")).thenComparingLong(r -> r.number("id")).reversed())
                .map(r -> r.number("id")).toList();
        a.slice.check("R13 own list ids are exactly the three new registrations", Set.copyOf(order).equals(Set.copyOf(mine)), order + " vs " + mine);
        a.page("R13 own list", EventsAssertions.actor(1), "/api/my/registrations", false, order, order.size(), 1, 20);
        ledger(a, "I01 after registrations");
    }

    /** R11: the registration records the fee of the moment, not a live reference to the event. */
    private static void snapshotFee(EventsAssertions a) throws Exception {
        a.fixture.fixtureStatement("UPDATE event SET fee=25.00 WHERE id=12");
        try {
            register(a, "R11 fee is a snapshot", 7, 12, "{}", "25.00");
        } finally {
            a.fixture.fixtureStatement("UPDATE event SET fee=15.50 WHERE id=12");
        }
        stats(a, "R11 revenue sums the stored fees", EventsAssertions.actor(2), 12, "40.50", 2);
    }

    // ---------------------------------------------------------------- invariants

    /** I01: every counter equals its stored rows, and every organizer's revenue/headcount equals a Java recomputation. */
    private static void ledger(EventsAssertions a, String label) throws Exception {
        var state = a.fixture.snapshot();
        a.slice.check(label + " every registered counter equals its stored rows and stays within capacity",
                EventsFixture.registeredMatchesRows(state), "checked all event and registration rows");
        var pairs = state.table("registration").stream().map(r -> r.number("event_id") + "/" + r.number("attendee_id")).toList();
        a.slice.check(label + " every attendee registered at most once per event", pairs.size() == Set.copyOf(pairs).size(), pairs.toString());
        for (var event : state.table("event")) {
            var rows = state.table("registration").stream().filter(r -> r.number("event_id") == event.number("id")).toList();
            BigDecimal sum = rows.stream().map(r -> r.decimal("fee")).reduce(BigDecimal.ZERO, BigDecimal::add);
            stats(a, label + " event " + event.number("id"), EventsAssertions.actor((int) event.number("organizer_id")),
                    event.number("id"), sum.toPlainString(), rows.size());
        }
    }

    // ---------------------------------------------------------------- real contention

    private static List<EventsConcurrentRequests.Call> calls(IntStream actors, long event) {
        return actors.mapToObj(actor -> new EventsConcurrentRequests.Call(actor, "/api/events/" + event + "/registrations", "{}")).toList();
    }

    static void capacityRace(EventsAssertions a) throws Exception {
        var before = a.fixture.snapshot();
        var results = EventsConcurrentRequests.run(a, "X01", 14, calls(IntStream.rangeClosed(3, 8), 14));
        statuses(a, "X01 capacity", results, 4, 2, "EventFull");
        var after = a.fixture.snapshot();
        var added = after.addedSince("registration", before);
        Set<Long> attendees = added.stream().map(r -> r.number("attendee_id")).collect(Collectors.toUnmodifiableSet());
        boolean correct = added.size() == 4 && attendees.size() == 4 && Set.of(3L, 4L, 5L, 6L, 7L, 8L).containsAll(attendees)
                && added.stream().allMatch(r -> r.number("event_id") == 14 && r.decimal("fee").compareTo(new BigDecimal("10.00")) == 0);
        a.slice.check("X01 exactly four distinct attendees hold the four seats", correct, added.toString());
        if (correct) {
            var expected = before.withEvent(14, "registered", 4L);
            for (var row : added) expected = expected.add("registration", row);
            a.expected("X01 capacity competition", expected, after);
        }
        for (var result : results) {
            if (result.response() == null) continue;
            if (result.response().statusCode() == 201) {
                long id = EventsAssertions.identifier(a.body("X01 winner", result.response()).path("id"));
                a.slice.check("X01 winner response matches its stored row",
                        EventsAssertions.matchesRegistration(a.body("X01 winner", result.response()), after, id, Duration.ofMillis(1)), result.response().body());
            } else a.error("X01 loser", result.response(), 409, "EventFull");
        }
        ledger(a, "X01 invariant");
        stats(a, "X01 organizer sees four paid seats", EventsAssertions.actor(1), 14, "40.00", 4);
    }

    static void sameUserRace(EventsAssertions a) throws Exception {
        var before = a.fixture.snapshot();
        var results = EventsConcurrentRequests.run(a, "X02", 15,
                IntStream.range(0, EventsConcurrentRequests.CLIENTS).mapToObj(i -> new EventsConcurrentRequests.Call(2, "/api/events/15/registrations", "{}")).toList());
        statuses(a, "X02 same user", results, 1, 5, "AlreadyRegistered");
        var after = a.fixture.snapshot();
        var added = after.addedSince("registration", before);
        boolean correct = added.size() == 1 && added.getFirst().number("event_id") == 15 && added.getFirst().number("attendee_id") == 2
                && added.getFirst().decimal("fee").compareTo(new BigDecimal("12.34")) == 0;
        a.slice.check("X02 exactly one registration survives", correct, added.toString());
        if (correct) a.expected("X02 repeated-registration competition",
                before.withEvent(15, "registered", 1L).add("registration", added.getFirst()), after);
        a.slice.check("X02 the counter rose once, not once per attempt", after.row("event", 15).number("registered") == 1, after.row("event", 15).toString());
        for (var result : results) {
            if (result.response() != null && result.response().statusCode() == 409) a.error("X02 loser", result.response(), 409, "AlreadyRegistered");
        }
        ledger(a, "X02 invariant");
    }

    private static void statuses(EventsAssertions a, String label, List<EventsConcurrentRequests.Outcome> results,
                                 int winners, int losers, String loserCode) {
        long good = results.stream().filter(r -> r.response() != null && r.response().statusCode() == 201).count();
        long refused = results.stream().filter(r -> r.response() != null && r.response().statusCode() == 409).count();
        a.slice.check(label + " has exactly " + winners + " successes and " + losers + " " + loserCode + " conflicts",
                good == winners && refused == losers && results.size() == EventsConcurrentRequests.CLIENTS,
                results.stream().map(r -> r.response() == null ? "ERROR " + r.error() : Integer.toString(r.response().statusCode())).toList().toString());
    }
}
