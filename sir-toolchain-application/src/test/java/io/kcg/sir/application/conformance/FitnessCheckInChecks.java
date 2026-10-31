package io.kcg.sir.application.conformance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Spec B01–B04, W01–W06, Q01–Q08, K01–K08 and I01–I03. Every read compares all four tables before and after; every
 * month expectation comes from the spec table and, in I01, is recomputed independently from the observed rows.
 */
final class FitnessCheckInChecks {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final YearMonth SEED_FIRST = YearMonth.of(2025, 12);
    private static final YearMonth SEED_LAST = YearMonth.of(2026, 4);
    private record Stat(int user, String month, long visits, String hours, String average) { }
    private record Endpoint(boolean post, String path, String body) { }
    private FitnessCheckInChecks() { }

    private static final List<Stat> SEED_STATS = List.of(
            new Stat(1, "2025-12", 1, "3.00", "3.00"), new Stat(1, "2026-01", 3, "3.25", "1.08"),
            new Stat(1, "2026-02", 3, "4.25", "1.42"), new Stat(1, "2026-03", 1, "1.25", "1.25"),
            new Stat(1, "2026-04", 0, "0", "0"), new Stat(2, "2026-02", 2, "2.01", "1.01"),
            new Stat(2, "2026-01", 0, "0", "0"), new Stat(3, "2026-02", 0, "0", "0"), new Stat(4, "2026-02", 0, "0", "0"));

    static void identityAndPrograms(FitnessAssertions a) throws Exception {
        a.page("B01 anonymous readiness", Map.of(), "/api/programs?page=1&size=10", true, List.of(10L, 12L, 11L), 3, 1, 10);
        a.page("B02 defaults", Map.of(), "/api/programs", true, List.of(10L, 12L, 11L), 3, 1, 20);
        a.page("B02 page default alone", Map.of(), "/api/programs?size=2", true, List.of(10L, 12L), 3, 1, 2);
        a.page("B02 size default alone", Map.of(), "/api/programs?page=2", true, List.of(), 3, 2, 20);
        a.page("B02 second page", Map.of(), "/api/programs?page=2&size=2", true, List.of(11L), 3, 2, 2);
        a.page("B02 past last page", Map.of(), "/api/programs?page=3&size=2", true, List.of(), 3, 3, 2);
        a.page("B02 inclusive upper bounds", Map.of(), "/api/programs?page=10000&size=100", true, List.of(), 3, 10000, 100);
        badPages(a, Map.of(), "B03", "/api/programs?");
        var endpoints = List.of(new Endpoint(true, "/api/check-ins", "{\"programId\":10,\"hours\":1.5}"),
                new Endpoint(false, "/api/my/month/visits?month=2026-01", ""),
                new Endpoint(false, "/api/my/month/hours?month=2026-01", ""),
                new Endpoint(false, "/api/my/month/average?month=2026-01", ""),
                new Endpoint(false, "/api/coach/check-ins?page=1&size=10", ""),
                new Endpoint(false, "/api/coach/month/visits?month=2026-01", ""));
        for (Endpoint endpoint : endpoints) {
            for (int index = 0; index < FitnessAssertions.INVALID_IDENTITIES.size(); index++) {
                a.unauthorized("B04 " + endpoint.path() + " invalid identity " + index, endpoint.post(),
                        FitnessAssertions.INVALID_IDENTITIES.get(index), endpoint.path(), endpoint.body());
            }
        }
    }

    private static void badPages(FitnessAssertions a, Map<String, String> headers, String id, String prefix) throws Exception {
        for (int page : List.of(0, -1, 10001)) a.badPage(id + " rejected page " + page + " on " + prefix, headers, prefix + "page=" + page + "&size=2");
        for (int size : List.of(0, -1, 101)) a.badPage(id + " rejected size " + size + " on " + prefix, headers, prefix + "page=1&size=" + size);
    }

    /** Logs one workout and checks the stored row, the response and the full-database delta. Returns the new row. */
    static FitnessFixture.Row log(FitnessAssertions a, String label, int actor, long program, String hours, String stored) throws Exception {
        var before = a.fixture.snapshot();
        Instant started = Instant.now();
        var response = a.post(label, FitnessAssertions.actor(actor), "/api/check-ins",
                "{\"programId\":" + program + ",\"hours\":" + hours + "}");
        Instant finished = Instant.now();
        a.slice.expectStatus(label, response, 201);
        long id = FitnessAssertions.identifier(a.body(label, response).path("id"));
        var after = a.fixture.snapshot();
        var added = after.addedSince("check_in", before);
        boolean newRow = id > 0 && added.size() == 1 && added.getFirst().number("id") == id;
        a.slice.check(label + " returns the one newly inserted numeric id", newRow, "id=" + id + "; inserted=" + added.size());
        if (!newRow) return null;
        var row = added.getFirst();
        a.slice.check(label + " stores the token's member, the input and the request time",
                row.number("member_id") == actor && row.number("program_id") == program
                        && row.decimal("hours").compareTo(new BigDecimal(stored)) == 0
                        && !row.time("checked_in_at").isBefore(started.minusMillis(1))
                        && !row.time("checked_in_at").isAfter(finished.plusMillis(1)),
                row + "; request=" + started + ".." + finished);
        a.expected(label, before.add("check_in", row), after);
        a.slice.check(label + " matches all JSON fields and JDBC values",
                FitnessAssertions.matchesCheckIn(a.body(label, response), after, id, java.time.Duration.ofMillis(1)), response.body());
        return row;
    }

    static void workouts(FitnessAssertions a) throws Exception {
        log(a, "W01 member logs", 1, 10, "1.50", "1.50");
        log(a, "W02 identity comes from the token, lower bound", 2, 11, "0.25", "0.25");
        log(a, "W03 upper bound, coach may log", 4, 12, "12", "12");
        log(a, "W03 equal-valued spelling", 3, 10, "1.2500", "1.25");
        var cases = new String[][] {{"omitted", null, "NotNull"}, {"null", "null", "NotNull"}, {"0.24", "0.24", null},
                {"12.01", "12.01", null}, {"0", "0", null}, {"-1", "-1", null}, {"1.234", "1.234", null}, {"100", "100", null}};
        for (String[] c : cases) {
            String label = "W04 hours " + c[0];
            var before = a.fixture.snapshot();
            String body = c[1] == null ? "{\"programId\":10}" : "{\"programId\":10,\"hours\":" + c[1] + "}";
            a.invalid(label, a.post(label, FitnessAssertions.actor(1), "/api/check-ins", body), "hours", c[2]);
            a.unchanged(label, before, a.fixture.snapshot());
        }
        for (String[] c : new String[][] {{"omitted", "{\"hours\":1}"}, {"null", "{\"programId\":null,\"hours\":1}"}}) {
            String label = "W05 programId " + c[0];
            var before = a.fixture.snapshot();
            a.invalid(label, a.post(label, FitnessAssertions.actor(1), "/api/check-ins", c[1]), "programId", "NotNull");
            a.unchanged(label, before, a.fixture.snapshot());
        }
        var before = a.fixture.snapshot();
        a.error("W05 unknown program", a.post("W05 unknown program", FitnessAssertions.actor(1), "/api/check-ins",
                "{\"programId\":999999,\"hours\":1}"), 404, "ProgramNotFound");
        a.unchanged("W05 unknown program", before, a.fixture.snapshot());
        before = a.fixture.snapshot();
        a.invalid("W06 forged member", a.post("W06 forged member", FitnessAssertions.actor(1), "/api/check-ins",
                "{\"programId\":10,\"hours\":1,\"member\":2}"), null, null);
        a.unchanged("W06 forged member", before, a.fixture.snapshot());
    }

    private static void monthly(FitnessAssertions a, String label, int user, String month, long visits, String hours, String average) throws Exception {
        var who = FitnessAssertions.actor(user);
        a.integer(label + " visits", who, "/api/my/month/visits?month=" + month, visits);
        a.decimal(label + " hours", who, "/api/my/month/hours?month=" + month, hours);
        a.decimal(label + " average", who, "/api/my/month/average?month=" + month, average);
    }

    static void statistics(FitnessAssertions a) throws Exception {
        for (Stat s : SEED_STATS) monthly(a, "Q " + s.user() + "/" + s.month(), s.user(), s.month(), s.visits(), s.hours(), s.average());
        // Q06: only the token decides whose rows are counted.
        var bob = FitnessAssertions.actor(2);
        a.integer("Q06 forged member visits", bob, "/api/my/month/visits?month=2026-01&member=1&memberId=1", 0);
        a.decimal("Q06 forged member hours", bob, "/api/my/month/hours?month=2026-01&member=1&memberId=1", "0");
        a.decimal("Q06 forged member average", bob, "/api/my/month/average?month=2026-01&member=1&memberId=1", "0");
        var alice = FitnessAssertions.actor(1);
        for (String month : List.of("2026-1", "2026-13", "2026-00", "2026-02-01", "abcd-ef", "0000-01", "0999-12", "1000-01")) {
            a.refusedRead("Q07 visits month " + month, alice, "/api/my/month/visits?month=" + month, 400, "BadPeriod");
        }
        // In Asia/Shanghai the last representable month starts 8 hours before the UTC limit, so it is valid, not an error.
        a.integer("Q07 largest representable month is valid", alice, "/api/my/month/visits?month=9999-12", 0);
        a.refusedRead("Q07 visits month omitted", alice, "/api/my/month/visits", 400, "BadPeriod");
        a.refusedRead("Q07 visits month empty", alice, "/api/my/month/visits?month=", 400, "BadPeriod");
        for (String endpoint : List.of("/api/my/month/hours", "/api/my/month/average")) {
            a.refusedRead("Q07 " + endpoint + " omitted", alice, endpoint, 400, "BadPeriod");
            a.refusedRead("Q07 " + endpoint + " month 13", alice, endpoint + "?month=2026-13", 400, "BadPeriod");
        }
        var dave = FitnessAssertions.actor(4);
        a.refusedRead("Q07 coach visits month omitted", dave, "/api/coach/month/visits", 400, "BadPeriod");
        a.refusedRead("Q07 coach visits month 13", dave, "/api/coach/month/visits?month=2026-13", 400, "BadPeriod");
    }

    /** Q08: new writes are counted at once and do not disturb seeded months. Skipped (reported) if the clock misleads. */
    static void statisticsAfterWrites(FitnessAssertions a) throws Exception {
        var first = log(a, "Q08 first workout", 1, 10, "1.50", "1.50");
        var second = log(a, "Q08 second workout", 1, 11, "2.25", "2.25");
        if (first == null || second == null) return;
        YearMonth m = month(first);
        if (!m.equals(month(second)) || !m.isBefore(SEED_FIRST) && !m.isAfter(SEED_LAST)) {
            a.slice.reportLine("Q08 NOT_RUN fixture clock: rows in " + m + " and " + month(second) + " overlap the seeded months");
            return;
        }
        monthly(a, "Q08 current month " + m, 1, m.toString(), 2, "3.75", "1.88");
        monthly(a, "Q08 seeded February is untouched", 1, "2026-02", 3, "4.25", "1.42");
    }

    private static YearMonth month(FitnessFixture.Row row) { return YearMonth.from(row.time("checked_in_at").atZone(SHANGHAI)); }

    static void coach(FitnessAssertions a) throws Exception {
        var dave = FitnessAssertions.actor(4);
        for (int member : List.of(1, 2)) {
            for (String path : List.of("/api/coach/check-ins?page=1&size=10", "/api/coach/month/visits?month=2026-02")) {
                a.refusedRead("K01 member " + member + " " + path, FitnessAssertions.actor(member), path, 403, "Forbidden");
            }
        }
        a.page("K02 all rows", dave, "/api/coach/check-ins", false, List.of(206L, 205L, 302L, 204L, 301L, 203L, 202L, 201L, 207L, 208L), 10, 1, 20);
        a.page("K02 size 3 page 1", dave, "/api/coach/check-ins?page=1&size=3", false, List.of(206L, 205L, 302L), 10, 1, 3);
        a.page("K02 size 3 page 2", dave, "/api/coach/check-ins?page=2&size=3", false, List.of(204L, 301L, 203L), 10, 2, 3);
        a.page("K02 size 3 page 3", dave, "/api/coach/check-ins?page=3&size=3", false, List.of(202L, 201L, 207L), 10, 3, 3);
        a.page("K02 size 3 page 4", dave, "/api/coach/check-ins?page=4&size=3", false, List.of(208L), 10, 4, 3);
        a.page("K02 size 3 page 5", dave, "/api/coach/check-ins?page=5&size=3", false, List.of(), 10, 5, 3);
        a.page("K03 member 1", dave, "/api/coach/check-ins?member=1", false, List.of(206L, 205L, 204L, 203L, 202L, 201L, 207L, 208L), 8, 1, 20);
        a.page("K03 member 2", dave, "/api/coach/check-ins?member=2", false, List.of(302L, 301L), 2, 1, 20);
        a.page("K03 member without rows", dave, "/api/coach/check-ins?member=3", false, List.of(), 0, 1, 20);
        a.page("K03 unknown member is an empty page", dave, "/api/coach/check-ins?member=999999", false, List.of(), 0, 1, 20);
        a.page("K03 member 1 second page of two", dave, "/api/coach/check-ins?member=1&size=2&page=2", false, List.of(204L, 203L), 8, 2, 2);
        a.page("K04 program 10", dave, "/api/coach/check-ins?program=10", false, List.of(206L, 204L, 301L, 203L, 201L, 208L), 6, 1, 20);
        a.page("K04 program 11", dave, "/api/coach/check-ins?program=11", false, List.of(205L, 302L, 202L, 207L), 4, 1, 20);
        a.page("K04 program without rows", dave, "/api/coach/check-ins?program=12", false, List.of(), 0, 1, 20);
        a.page("K04 unknown program is an empty page", dave, "/api/coach/check-ins?program=999999", false, List.of(), 0, 1, 20);
        a.page("K05 member 1 and program 11", dave, "/api/coach/check-ins?member=1&program=11", false, List.of(205L, 202L, 207L), 3, 1, 20);
        a.page("K05 member 2 and program 10", dave, "/api/coach/check-ins?member=2&program=10", false, List.of(301L), 1, 1, 20);
        a.page("K05 member 2 and program 12", dave, "/api/coach/check-ins?member=2&program=12", false, List.of(), 0, 1, 20);
        badPages(a, dave, "K06", "/api/coach/check-ins?");
        long[] expected = {5, 3, 2, 0, 0};
        String[] paths = {"month=2026-02", "month=2026-02&program=10", "month=2026-02&program=11", "month=2026-02&program=12", "month=2026-02&program=999999"};
        for (int i = 0; i < paths.length; i++) a.integer("K07 coach visits " + paths[i], dave, "/api/coach/month/visits?" + paths[i], expected[i]);
        for (String[] c : new String[][] {{"2026-01", "3"}, {"2026-03", "1"}, {"2025-12", "1"}, {"2026-04", "0"}}) {
            a.integer("K07 coach visits " + c[0], dave, "/api/coach/month/visits?month=" + c[0], Long.parseLong(c[1]));
        }
    }

    /** K08: a fresh write is visible to the coach, first in the descending order. */
    static void coachSeesNewData(FitnessAssertions a) throws Exception {
        var row = log(a, "K08 member logs swim", 1, 12, "1", "1");
        if (row == null) return;
        a.page("K08 coach list starts with the new row", FitnessAssertions.actor(4), "/api/coach/check-ins?page=1&size=3", false,
                List.of(row.number("id"), 206L, 205L), 11, 1, 3);
        a.integer("K08 coach month count for the new program", FitnessAssertions.actor(4),
                "/api/coach/month/visits?month=" + month(row) + "&program=12", 1);
    }

    /** I01: recompute every statistic from the observed rows with java.time and BigDecimal, then compare. */
    static void recomputation(FitnessAssertions a) throws Exception {
        var rows = new ArrayList<FitnessFixture.Row>();
        rows.add(log(a, "I01 alice", 1, 10, "1.50", "1.50"));
        rows.add(log(a, "I01 bob", 2, 11, "2.25", "2.25"));
        rows.add(log(a, "I01 dave", 4, 12, "0.25", "0.25"));
        if (rows.contains(null)) return;
        var state = a.fixture.snapshot();
        var months = new ArrayList<YearMonth>();
        for (YearMonth m = SEED_FIRST; !m.isAfter(SEED_LAST); m = m.plusMonths(1)) months.add(m);
        rows.forEach(r -> { if (!months.contains(month(r))) months.add(month(r)); });
        for (int user = 1; user <= 4; user++) {
            for (YearMonth m : months) {
                int member = user;
                var s = compute(state, r -> r.number("member_id") == member, m);
                monthly(a, "I01 user " + user + " " + m, user, m.toString(), s.visits(), s.hours(), s.average());
            }
        }
        var dave = FitnessAssertions.actor(4);
        for (YearMonth m : months) {
            a.integer("I01 coach all programs " + m, dave, "/api/coach/month/visits?month=" + m, compute(state, r -> true, m).visits());
            for (long program : List.of(10L, 11L, 12L)) {
                a.integer("I01 coach program " + program + " " + m, dave, "/api/coach/month/visits?month=" + m + "&program=" + program,
                        compute(state, r -> r.number("program_id") == program, m).visits());
            }
        }
    }

    private static Stat compute(FitnessFixture.State state, Predicate<FitnessFixture.Row> filter, YearMonth m) {
        var selected = state.table("check_in").stream().filter(filter).filter(r -> month(r).equals(m)).toList();
        BigDecimal sum = selected.stream().map(r -> r.decimal("hours")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = selected.isEmpty() ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(selected.size()), 2, RoundingMode.HALF_UP);
        return new Stat(0, m.toString(), selected.size(), sum.toPlainString(), average.toPlainString());
    }
}
