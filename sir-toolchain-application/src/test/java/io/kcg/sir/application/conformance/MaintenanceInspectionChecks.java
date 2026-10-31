package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.MaintenanceWorkOrderChecks.*;
import static io.kcg.sir.application.conformance.BoundedProblemState.Row.of;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spec R01–R05 / E01–E04: exact bounded-loop results, not just affected-row counts. */
final class MaintenanceInspectionChecks {
    static final String FIRST = "2000-01-01T06:00:00Z";
    static final String CUTOFF = "2000-01-02T00:00:00Z";
    private MaintenanceInspectionChecks() { }
    static String generatePath(long facility) { return "/api/facilities/" + facility + "/inspection-series"; }
    static String closePath(long facility) { return "/api/facilities/" + facility + "/close-overdue-inspections"; }
    static String plan(String title, String first, int interval, int occurrences) throws Exception {
        return json("title", title, "firstDueAt", first, "intervalDays", interval, "occurrences", occurrences);
    }

    static void generation(BoundedProblemHttp a) throws Exception {
        generate(a, "R01 three inspections", "Routine", FIRST, 3, 3);
        generate(a, "R02 identical plan allowed", "Routine", FIRST, 3, 3);
        generate(a, "R02 limit 24 and interval 30", "Boundary", FIRST, 30, 24);
    }

    private static void generate(BoundedProblemHttp a, String label, String title, String first, int interval, int occurrences) throws Exception {
        var before = a.fixture.snapshot();
        a.empty(label, a.send(label, "POST", 1, generatePath(12), plan(title, first, interval, occurrences)), 201);
        var after = a.fixture.snapshot();
        var expected = generatedState(a, label, before, after, 12, title, Instant.parse(first), interval, occurrences, 0);
        if (expected != null) a.expected(label, expected, after);
        var ids = inspectionIds(after, 12);
        a.page(label + " read-back", 1, "/api/facilities/12/inspections?size=100", INSPECTION, ids, ids.size(), 1, 100);
    }

    static BoundedProblemState generatedState(BoundedProblemHttp a, String label, BoundedProblemState before,
            BoundedProblemState after, long facility, String title, Instant first, int interval, int occurrences, long closed) {
        var added = after.addedSince("inspection_order", before).stream()
                .sorted(Comparator.comparing((BoundedProblemState.Row r) -> (Instant) r.value("due_at")).thenComparingLong(r -> r.number("id"))).toList();
        a.slice.check(label + " exact new inspection count", added.size() == occurrences, "inserted=" + added.size());
        if (added.size() != occurrences) return null;
        var expected = before;
        for (int index = 0; index < occurrences; index++) {
            expected = expected.add("inspection_order", of("id", added.get(index).number("id"), "facility_id", facility,
                    "title", title, "due_at", first.plus((long) index * interval, ChronoUnit.DAYS), "closed", closed));
        }
        return expected;
    }

    static void rejectedGeneration(BoundedProblemHttp a) throws Exception {
        for (int count : List.of(25, Integer.MAX_VALUE)) {
            a.refuse("R03 occurrences=" + count, "POST", 1, generatePath(12), plan("Routine", FIRST, 30, count), 409, "TooManyOccurrences");
        }
        Map<String, Object> valid = Map.of("title", "Routine", "firstDueAt", FIRST, "intervalDays", 1, "occurrences", 1);
        for (String field : List.of("occurrences", "intervalDays", "firstDueAt", "title")) {
            String code = field.equals("title") ? "NotBlank" : "NotNull";
            var absent = new LinkedHashMap<>(valid);
            absent.remove(field);
            a.invalidRequest("R04 omitted " + field, "POST", 1, generatePath(12), BoundedProblemHttp.json(absent), field, code);
            var cleared = new LinkedHashMap<>(valid);
            cleared.put(field, null);
            a.invalidRequest("R04 null " + field, "POST", 1, generatePath(12), BoundedProblemHttp.json(cleared), field, code);
        }
        for (int count : List.of(0, -1)) a.invalidRequest("R04 count " + count, "POST", 1, generatePath(12), plan("Routine", FIRST, 1, count), "occurrences", null);
        for (int interval : List.of(0, 31)) a.invalidRequest("R04 interval " + interval, "POST", 1, generatePath(12), plan("Routine", FIRST, interval, 1), "intervalDays", null);
        a.invalidRequest("R04 blank title", "POST", 1, generatePath(12), plan("   ", FIRST, 1, 1), "title", "NotBlank");
        a.invalidRequest("R04 long title", "POST", 1, generatePath(12), plan("t".repeat(101), FIRST, 1, 1), "title", null);
        a.refuse("R05 unauthorized generation", "POST", 2, generatePath(10), plan("Routine", FIRST, 1, 1), 403, "Forbidden");
        a.refuse("R05 unknown generation facility", "POST", 1, generatePath(999999), plan("Routine", FIRST, 1, 1), 404, "FacilityNotFound");
        a.refuse("R05 unauthorized closure", "POST", 2, closePath(10), json("cutoff", CUTOFF), 403, "Forbidden");
        a.refuse("R05 unknown closure facility", "POST", 1, closePath(999999), json("cutoff", CUTOFF), 404, "FacilityNotFound");
    }

    static BoundedProblemState closedState(BoundedProblemState before, long facility, Instant cutoff) {
        var expected = before;
        for (var row : before.table("inspection_order")) {
            if (row.number("facility_id") == facility && row.number("closed") == 0 && ((Instant) row.value("due_at")).isBefore(cutoff)) {
                expected = expected.with("inspection_order", row.number("id"), "closed", 1L);
            }
        }
        return expected;
    }

    static void closure(BoundedProblemHttp a) throws Exception {
        close(a, "E01 exact strict-before set", 10);
        close(a, "E01 repeat is empty success", 10);
        close(a, "E02 empty facility", 12);
        a.invalidRequest("E02 omitted cutoff", "POST", 1, closePath(10), "{}", "cutoff", "NotNull");
        a.invalidRequest("E02 null cutoff", "POST", 1, closePath(10), "{\"cutoff\":null}", "cutoff", "NotNull");
        a.page("E04 closed inspections remain listed", 1, INSPECTIONS, INSPECTION, List.of(201L, 202L, 204L, 203L, 206L), 5, 1, 20);
        a.getRow("E04 work order unchanged", 1, path(102), WORK, 102);
        a.page("E04 work scope unchanged", 1, WORKS, WORK, List.of(101L, 102L, 105L, 106L), 4, 1, 20);
    }

    private static void close(BoundedProblemHttp a, String label, long facility) throws Exception {
        var before = a.fixture.snapshot();
        a.empty(label, a.send(label, "POST", 1, closePath(facility), json("cutoff", CUTOFF)), 200);
        a.expected(label, closedState(before, facility, Instant.parse(CUTOFF)), a.fixture.snapshot());
    }

    static void closureBoundaries(BoundedProblemHttp a) throws Exception {
        for (int count : List.of(1, 50, 51)) {
            if (!a.fixture.reset()) return;
            for (int index = 1; index <= count; index++) {
                // TEST_FIXTURE_SQL: constants and bounded fixture indices only, before any HTTP request.
                a.fixture.statement("INSERT INTO inspection_order (id,facility_id,title,due_at,closed) VALUES ("
                        + (1000 + index) + ",12,'limit-" + index + "','2000-01-01 00:00:00.000000',0)");
            }
            if (count == 51) a.refuse("E03 51 rejects whole batch", "POST", 1, closePath(12), json("cutoff", CUTOFF), 409, "TooManyInspections");
            else close(a, "E03 exactly " + count + " closed", 12);
        }
    }
}
