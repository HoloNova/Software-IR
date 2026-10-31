package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.StaffingRosterChecks.*;
import static io.kcg.sir.application.conformance.BoundedProblemState.Row.of;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** G01–G07: empty shifts, exact weekly dates, pre-body bounds and late unique-conflict rollback. */
final class StaffingWeeklyChecks {
    static final String FIRST = "2026-02-02";
    static final String PLAN = "{\"firstDay\":\"2026-02-02\",\"band\":\"OPENING\",\"weeks\":3}";
    private StaffingWeeklyChecks() { }
    static String path(long store) { return "/api/stores/" + store + "/weekly-shifts"; }
    static String plan(String first, String band, int weeks) throws Exception { return json("firstDay", first, "band", band, "weeks", weeks); }

    static void generation(BoundedProblemHttp a) throws Exception {
        generate(a, "G01 three empty shifts", FIRST, "OPENING", 3, PLAN);
        a.refuse("G02 repeated plan", "POST", 1, path(12), PLAN, 409, "ShiftExists");
        generate(a, "G02 other band on same days", FIRST, "CLOSING", 3, plan(FIRST, "CLOSING", 3));
    }

    static void boundsAndLateRollback(BoundedProblemHttp a) throws Exception {
        generate(a, "G03 eight weeks", FIRST, "OPENING", 8, plan(FIRST, "OPENING", 8));
        for (int weeks : List.of(9, Integer.MAX_VALUE)) {
            if (!a.fixture.reset()) return;
            a.refuse("G03 over limit " + weeks, "POST", 1, path(12), plan(FIRST, "OPENING", weeks), 409, "TooManyWeeks");
        }
        if (!a.fixture.reset()) return;
        a.refuse("G04 third week conflicts and earlier writes roll back", "POST", 1, path(10), plan("2026-01-07", "CLOSING", 3), 409, "ShiftExists");
    }

    static void invalidPlans(BoundedProblemHttp a) throws Exception {
        Map<String, Object> valid = Map.of("firstDay", FIRST, "band", "OPENING", "weeks", 3);
        for (String field : List.of("firstDay", "band", "weeks")) {
            var omitted = new LinkedHashMap<>(valid);
            omitted.remove(field);
            a.invalidRequest("G05 missing " + field, "POST", 1, path(12), BoundedProblemHttp.json(omitted), field, "NotNull");
            var cleared = new LinkedHashMap<>(valid);
            cleared.put(field, null);
            a.invalidRequest("G05 null " + field, "POST", 1, path(12), BoundedProblemHttp.json(cleared), field, "NotNull");
        }
        for (int weeks : List.of(0, -1)) a.invalidRequest("G05 weeks " + weeks, "POST", 1, path(12), plan(FIRST, "OPENING", weeks), "weeks", null);
        a.invalidRequest("G05 unreadable day", "POST", 1, path(12), plan("not-a-date", "OPENING", 3), null, null);
        a.invalidRequest("G05 invalid enum", "POST", 1, path(12), plan(FIRST, "NIGHT", 3), null, null);
        a.refuse("G06 forbidden", "POST", 2, path(12), PLAN, 403, "Forbidden");
        a.refuse("G06 unknown store", "POST", 1, path(999999), PLAN, 404, "StoreNotFound");
        generate(a, "G06 path store wins", FIRST, "OPENING", 3, json("storeId", 10, "firstDay", FIRST, "band", "OPENING", "weeks", 3));
    }

    static void leapYear(BoundedProblemHttp a) throws Exception {
        generate(a, "G07 leap-year month crossing", "2028-02-23", "OPENING", 3, plan("2028-02-23", "OPENING", 3));
    }

    private static void generate(BoundedProblemHttp a, String label, String first, String band, int weeks, String body) throws Exception {
        var before = a.fixture.snapshot();
        a.empty(label, a.send(label, "POST", 1, path(12), body), 201);
        var after = a.fixture.snapshot();
        var expected = generatedState(a, label, before, after, LocalDate.parse(first), band, weeks);
        if (expected != null) a.expected(label, expected, after);
        var ids = shiftIds(after, 12);
        a.page(label + " read-back", 1, shifts(12) + "?size=100", SHIFT, ids, ids.size(), 1, 100);
        StaffingRosterChecks.recompute(a, label + " independent counts");
    }

    static BoundedProblemState generatedState(BoundedProblemHttp a, String label, BoundedProblemState before,
            BoundedProblemState after, LocalDate first, String band, int weeks) {
        var added = after.addedSince("shift", before).stream()
                .sorted(Comparator.comparing((BoundedProblemState.Row r) -> (LocalDate) r.value("day")).thenComparingLong(r -> r.number("id"))).toList();
        a.slice.check(label + " exact generated shift count", added.size() == weeks, "inserted=" + added.size());
        if (added.size() != weeks) return null;
        var expected = before;
        for (int index = 0; index < weeks; index++) expected = expected.add("shift", of("id", added.get(index).number("id"),
                "store_id", 12L, "day", first.plusWeeks(index), "band", band));
        return expected;
    }
}
