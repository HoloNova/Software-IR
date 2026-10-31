package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Negative controls for the oracle itself; these never contact MySQL or start an application. */
class BoundedProblemOracleTest {
    private static final BoundedProblemHttp.Projection WORK = new BoundedProblemHttp.Projection("work_order",
            Map.of("id", "id", "title", "title", "assignee", "assignee", "note", "note", "removed", "removed", "version", "version"), Set.of("removed"));
    private static final String VALID = "{\"id\":101,\"title\":\"Leak\",\"assignee\":null,\"note\":null,\"removed\":false,\"version\":0}";

    @Test
    void nullableReferencesAndNotesRequireExplicitNullNotMissingOrQuotedIds() throws Exception {
        assertTrue(matches(VALID));
        for (String invalid : List.of(VALID.replace(",\"assignee\":null", ""), VALID.replace("\"assignee\":null", "\"assignee\":\"3\""),
                VALID.replace("\"note\":null", "\"note\":\"\""), VALID.replace("\"id\":101", "\"id\":\"101\""),
                VALID.replace("\"version\":0", "\"version\":0.0"), VALID.replace("\"removed\":false", "\"removed\":0"),
                VALID.replace("\"title\":\"Leak\"", "\"title\":\"other\""), VALID.replace("{", "{\"manager\":1,"))) {
            assertFalse(matches(invalid), invalid);
        }
    }

    @Test
    void optionalReferenceHasNoInventedIdSuffixAndKnownValuesAreNumeric() throws Exception {
        var assigned = state().with("work_order", 101, "assignee", 3L, "note", "fixed");
        String text = VALID.replace("\"assignee\":null", "\"assignee\":3").replace("\"note\":null", "\"note\":\"fixed\"");
        assertTrue(BoundedProblemHttp.matchesRow(BoundedProblemHttp.decode(text), WORK, assigned, 101));
        assertFalse(BoundedProblemHttp.matchesRow(BoundedProblemHttp.decode(text.replace("\"assignee\":", "\"assigneeId\":")), WORK, assigned, 101));
    }

    @Test
    void pagesRejectDroppedDuplicateExtraOrReorderedRowsAndIncorrectEmptyTotals() throws Exception {
        var two = state().add("work_order", state().row("work_order", 101).with("id", 102L));
        String page = "{\"total\":2,\"page\":1,\"size\":20,\"records\":[" + VALID + "," + VALID.replace("101", "102") + "]}";
        var body = BoundedProblemHttp.decode(page);
        assertTrue(BoundedProblemHttp.matchesPage(body, WORK, two, List.of(101L, 102L), 2, 1, 20));
        assertFalse(BoundedProblemHttp.matchesPage(body, WORK, two, List.of(102L, 101L), 2, 1, 20));
        assertFalse(BoundedProblemHttp.matchesPage(body, WORK, two, List.of(101L), 2, 1, 20));
        assertFalse(BoundedProblemHttp.matchesPage(BoundedProblemHttp.decode(page.replace("102", "101")), WORK, two, List.of(101L, 102L), 2, 1, 20));
        var empty = BoundedProblemHttp.decode("{\"total\":2,\"page\":3,\"size\":1,\"records\":[]}");
        assertTrue(BoundedProblemHttp.matchesPage(empty, WORK, two, List.of(), 2, 3, 1));
        assertFalse(BoundedProblemHttp.matchesPage(empty, WORK, two, List.of(), 0, 3, 1));
        assertFalse(BoundedProblemHttp.matchesPage(BoundedProblemHttp.decode(page.replace("\"total\":2", "\"total\":\"2\"")), WORK, two, List.of(101L, 102L), 2, 1, 20));
    }

    @Test
    void momentsCompareUtcInstantsWithoutToleranceAndDaysCannotDrift() throws Exception {
        var projection = new BoundedProblemHttp.Projection("inspection", Map.of("id", "id", "dueAt", "due_at", "day", "day"), Set.of());
        var snapshot = new BoundedProblemState(Map.of("inspection", List.of(BoundedProblemState.Row.of("id", 1L,
                "due_at", Instant.parse("2026-01-01T00:00:00Z"), "day", LocalDate.of(2026, 1, 1)))));
        String json = "{\"id\":1,\"dueAt\":\"2026-01-01T00:00:00Z\",\"day\":\"2026-01-01\"}";
        assertTrue(BoundedProblemHttp.matchesRow(BoundedProblemHttp.decode(json), projection, snapshot, 1));
        assertFalse(BoundedProblemHttp.matchesRow(BoundedProblemHttp.decode(json.replace("00:00:00Z", "00:00:00.001Z")), projection, snapshot, 1));
        assertFalse(BoundedProblemHttp.matchesRow(BoundedProblemHttp.decode(json.replace("\"day\":\"2026-01-01\"", "\"day\":\"2025-12-31\"")), projection, snapshot, 1));
    }

    @Test
    void strictJsonRejectsDuplicateFieldsTrailingDocumentsAndEmptyBodies() {
        for (String invalid : List.of("{\"id\":1,\"id\":2}", "{} {}", "")) {
            assertThrows(IOException.class, () -> BoundedProblemHttp.decode(invalid));
        }
    }

    @Test
    void errorsRequireTheFullDeclaredEnvelope() throws Exception {
        assertTrue(BoundedProblemHttp.matchesError(BoundedProblemHttp.decode("{\"code\":\"StaleVersion\",\"message\":\"StaleVersion\",\"fields\":[]}"), "StaleVersion"));
        for (String invalid : List.of("{\"code\":\"StaleVersion\"}", "{\"code\":\"StaleVersion\",\"message\":\"wrong\",\"fields\":[]}",
                "{\"code\":\"StaleVersion\",\"message\":\"StaleVersion\",\"fields\":[{}]}")) {
            assertFalse(BoundedProblemHttp.matchesError(BoundedProblemHttp.decode(invalid), "StaleVersion"));
        }
    }

    @Test
    void snapshotsAndAllowedDeltasAreImmutableAndIncludeEveryTableAndColumn() {
        var before = state();
        var after = before.with("work_order", 101, "note", "fixed", "version", 1L);
        assertNull(before.row("work_order", 101).value("note"));
        assertEquals(0, before.row("work_order", 101).number("version"));
        assertEquals("fixed", after.row("work_order", 101).text("note"));
        assertNotEquals(after, after.with("user", 1, "name", "forged"));
        assertThrows(UnsupportedOperationException.class, () -> before.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> before.row("work_order", 101).cells().clear());
        assertThrows(IllegalArgumentException.class, () -> before.add("work_order", before.row("work_order", 101)));
        var added = after.add("work_order", before.row("work_order", 101).with("id", 103L));
        assertEquals(List.of(103L), added.addedSince("work_order", before).stream().map(r -> r.number("id")).toList());
    }

    @Test
    void snapshotCopiesDoNotRetainMutableInputMapsOrLists() {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", 1L);
        map.put("note", null);
        var row = new BoundedProblemState.Row(map);
        map.put("note", "mutated");
        assertNull(row.value("note"));
        assertThrows(IllegalArgumentException.class, () -> BoundedProblemState.Row.of("id"));
        assertThrows(IllegalArgumentException.class, () -> BoundedProblemState.Row.of("id", 1L, "id", 2L));
    }

    @Test
    void jdbcBooleansAndNarrowNumbersNormalizeButNullAndCalendarDatesSurvive() {
        assertEquals(1L, BoundedProblemFixture.normalize(true));
        assertEquals(0L, BoundedProblemFixture.normalize(false));
        assertEquals(7L, BoundedProblemFixture.normalize(7));
        assertEquals(9L, BoundedProblemFixture.normalize((short) 9));
        assertNull(BoundedProblemFixture.normalize(null));
        assertEquals(LocalDate.of(2028, 3, 1), BoundedProblemFixture.normalize(LocalDate.of(2028, 3, 1)));
    }

    @Test
    void completedHttpResponsesAfterTheRoundDeadlineCannotQualify(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var fixture = new BusinessSliceHarness.Fixture("test", "/valid/maintenance-work-order.sir",
                "/conformance/mysql/maintenance-work-order-ddl.sql", "/conformance/mysql/maintenance-work-order-seed.sql",
                "work_order", "/api/facilities", "test-report.txt", "test", "kcg.maintenance-conformance.enabled");
        var slice = new BusinessSliceHarness(fixture, BusinessSliceHarnessEvidenceTest.environment(temp.resolve("work"), temp.resolve("evidence"), fixture.enabledProperty()));
        var call = new BoundedProblemRace.Call("PATCH", 1, "/api/work-orders/105", "{}");
        List<Future<BoundedProblemRace.Outcome>> futures = List.of(CompletableFuture.completedFuture(
                new BoundedProblemRace.Outcome(call, new HttpAssertionClient.Response(200, "{}"), null)));
        var outcomes = BoundedProblemRace.collect(slice, "late", futures, List.of(call), System.nanoTime() - 1);
        assertNotNull(outcomes.getFirst().error());
        assertEquals(200, outcomes.getFirst().response().statusCode());
        assertFalse(slice.passed());
    }

    @Test
    void closureUsesTheStrictPredicateAndPreservesUnrelatedRows() {
        var first = Instant.parse("2000-01-01T00:00:00Z");
        var cutoff = Instant.parse("2000-01-02T00:00:00Z");
        var before = new BoundedProblemState(Map.of("inspection_order", List.of(
                BoundedProblemState.Row.of("id", 1L, "facility_id", 10L, "due_at", first, "closed", 0L),
                BoundedProblemState.Row.of("id", 2L, "facility_id", 10L, "due_at", cutoff, "closed", 0L),
                BoundedProblemState.Row.of("id", 3L, "facility_id", 11L, "due_at", first, "closed", 0L),
                BoundedProblemState.Row.of("id", 4L, "facility_id", 10L, "due_at", first, "closed", 1L)),
                "work_order", List.of(BoundedProblemState.Row.of("id", 101L, "version", 2L))));
        var closed = MaintenanceInspectionChecks.closedState(before, 10, cutoff);
        assertEquals(before.with("inspection_order", 1, "closed", 1L), closed);
        assertEquals(closed, MaintenanceInspectionChecks.closedState(closed, 10, cutoff), "idempotent empty match set");
    }

    @Test
    void assignmentOracleRejectsCrossStoreAndDuplicatePairsButAllowsManyEmployees() {
        var before = new BoundedProblemState(Map.of(
                "employee", List.of(BoundedProblemState.Row.of("id", 101L, "store_id", 10L),
                        BoundedProblemState.Row.of("id", 102L, "store_id", 10L), BoundedProblemState.Row.of("id", 104L, "store_id", 11L)),
                "shift", List.of(BoundedProblemState.Row.of("id", 201L, "store_id", 10L)),
                "assignment", List.of(BoundedProblemState.Row.of("id", 301L, "employee_id", 101L, "shift_id", 201L))));
        assertTrue(StaffingRosterChecks.validRelations(before));
        assertTrue(StaffingRosterChecks.validRelations(before.add("assignment", BoundedProblemState.Row.of("id", 302L, "employee_id", 102L, "shift_id", 201L))));
        assertFalse(StaffingRosterChecks.validRelations(before.add("assignment", BoundedProblemState.Row.of("id", 302L, "employee_id", 104L, "shift_id", 201L))));
        assertFalse(StaffingRosterChecks.validRelations(before.add("assignment", BoundedProblemState.Row.of("id", 302L, "employee_id", 101L, "shift_id", 201L))));
        assertFalse(StaffingRosterChecks.validRelations(before.with("assignment", 301, "employee_id", 999999L)));
    }

    @Test
    void slotIdentityIncludesStoreDayAndBandNotExclusiveDailyOccupancy() {
        var shift = BoundedProblemState.Row.of("id", 201L, "store_id", 10L, "day", LocalDate.of(2026, 1, 5), "band", "OPENING");
        var before = new BoundedProblemState(Map.of("shift", List.of(shift)));
        assertTrue(StaffingRosterChecks.uniqueShifts(before));
        assertTrue(StaffingRosterChecks.uniqueShifts(before.add("shift", shift.with("id", 202L, "band", "CLOSING"))));
        assertTrue(StaffingRosterChecks.uniqueShifts(before.add("shift", shift.with("id", 202L, "store_id", 11L))));
        assertTrue(StaffingRosterChecks.uniqueShifts(before.add("shift", shift.with("id", 202L, "day", LocalDate.of(2026, 1, 12)))));
        assertFalse(StaffingRosterChecks.uniqueShifts(before.add("shift", shift.with("id", 202L))));
    }

    @Test
    void scalarCountsAreBareIntegralJsonAndStayInSignedLongRange() throws Exception {
        assertTrue(BoundedProblemHttp.number(BoundedProblemHttp.decode("0"), 0));
        assertTrue(BoundedProblemHttp.number(BoundedProblemHttp.decode("9223372036854775807"), Long.MAX_VALUE));
        for (String text : List.of("\"3\"", "3.0", "{\"headcount\":3}", "9223372036854775808", "null")) {
            assertFalse(BoundedProblemHttp.number(BoundedProblemHttp.decode(text), 3), text);
        }
    }

    private static boolean matches(String text) throws Exception {
        return BoundedProblemHttp.matchesRow(BoundedProblemHttp.decode(text), WORK, state(), 101);
    }

    private static BoundedProblemState state() {
        return new BoundedProblemState(Map.of("user", List.of(BoundedProblemState.Row.of("id", 1L, "name", "alice")),
                "work_order", List.of(BoundedProblemState.Row.of("id", 101L, "title", "Leak", "assignee", null,
                        "note", null, "removed", 0L, "version", 0L))));
    }
}
