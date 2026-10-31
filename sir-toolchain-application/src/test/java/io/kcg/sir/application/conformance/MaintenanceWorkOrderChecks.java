package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.BoundedProblemState.Row.of;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Approved maintenance spec B/W/D/P/L checks. No business decision is inferred from a candidate response. */
final class MaintenanceWorkOrderChecks {
    static final BoundedProblemHttp.Projection FACILITY = new BoundedProblemHttp.Projection("facility", Map.of("id", "id", "name", "name"), Set.of());
    static final BoundedProblemHttp.Projection WORK = new BoundedProblemHttp.Projection("work_order", Map.of(
            "id", "id", "facility", "facility_id", "reporter", "reporter_id", "title", "title", "assignee", "assignee",
            "note", "note", "removed", "removed", "version", "version"), Set.of("removed"));
    static final BoundedProblemHttp.Projection INSPECTION = new BoundedProblemHttp.Projection("inspection_order", Map.of(
            "id", "id", "facility", "facility_id", "title", "title", "dueAt", "due_at", "closed", "closed"), Set.of("closed"));
    static final String WORKS = "/api/facilities/10/work-orders";
    static final String INSPECTIONS = "/api/facilities/10/inspections";
    private record Endpoint(String method, String path, String body) { }
    private MaintenanceWorkOrderChecks() { }

    static String json(Object... pairs) throws Exception { return BoundedProblemHttp.json(of(pairs).cells()); }
    static String patchBody(long version, String changes) { return "{\"expectedVersion\":" + version + ",\"changes\":" + changes + "}"; }
    static String path(long id) { return "/api/work-orders/" + id; }

    static boolean validSeed(BoundedProblemState s) {
        return s.table("access_token").stream().allMatch(r -> s.has("user", r.number("owner_id")))
                && s.table("facility").stream().allMatch(r -> s.has("user", r.number("manager_id")))
                && s.table("work_order").stream().allMatch(r -> s.has("facility", r.number("facility_id"))
                        && s.has("user", r.number("reporter_id")) && (r.value("assignee") == null || s.has("user", r.number("assignee"))))
                && s.table("inspection_order").stream().allMatch(r -> s.has("facility", r.number("facility_id")))
                && s.row("work_order", 102).number("version") == 2 && s.row("work_order", 103).number("removed") == 1
                && workIds(s, 10, null).equals(List.of(101L, 102L, 105L, 106L));
    }

    static void identityAndCreation(BoundedProblemHttp a) throws Exception {
        a.page("B01 anonymous facilities", 0, "/api/facilities", FACILITY, List.of(10L, 12L, 11L), 3, 1, 20);
        a.page("B01 first page", 0, "/api/facilities?size=2", FACILITY, List.of(10L, 12L), 3, 1, 2);
        a.page("B01 second page", 0, "/api/facilities?size=2&page=2", FACILITY, List.of(11L), 3, 2, 2);
        var endpoints = List.of(new Endpoint("POST", "/api/work-orders", json("facilityId", 10, "title", "auth")),
                new Endpoint("PATCH", path(102), patchBody(2, "{\"note\":\"auth\"}")), new Endpoint("GET", path(102), ""),
                new Endpoint("GET", WORKS, ""), new Endpoint("GET", INSPECTIONS, ""),
                new Endpoint("POST", MaintenanceInspectionChecks.generatePath(10), MaintenanceInspectionChecks.plan("auth", "2000-01-01T00:00:00Z", 1, 1)),
                new Endpoint("POST", MaintenanceInspectionChecks.closePath(10), "{\"cutoff\":\"2000-01-02T00:00:00Z\"}"));
        for (var endpoint : endpoints) for (var identity : a.invalidIdentities()) {
            a.unauthorized("B02 " + endpoint.method() + " " + endpoint.path(), endpoint.method(), identity, endpoint.path(), endpoint.body());
        }
        create(a, "W01 reporter dave", 4, "Pipe");
        create(a, "W01 reporter bob is not manager", 2, "Pipe B");
        create(a, "W03 maximum title", 1, "x".repeat(100));
        for (String title : List.of("null", "\"\"", "\"   \"", "\"" + "x".repeat(101) + "\"")) {
            a.invalidRequest("W02 invalid title " + title.length(), "POST", 1, "/api/work-orders", "{\"facilityId\":10,\"title\":" + title + "}", "title", title.length() < 20 ? "NotBlank" : null);
        }
        a.invalidRequest("W02 omitted title", "POST", 1, "/api/work-orders", "{\"facilityId\":10}", "title", "NotBlank");
        a.invalidRequest("W02 omitted facility", "POST", 1, "/api/work-orders", "{\"title\":\"x\"}", "facilityId", "NotNull");
        a.invalidRequest("W02 null facility", "POST", 1, "/api/work-orders", "{\"facilityId\":null,\"title\":\"x\"}", "facilityId", "NotNull");
        a.refuse("W02 unknown facility", "POST", 1, "/api/work-orders", json("facilityId", 999999, "title", "x"), 404, "FacilityNotFound");
        for (String field : List.of("reporter", "version")) a.invalidRequest("W03 forged " + field, "POST", 1, "/api/work-orders",
                json("facilityId", 10, "title", "x", field, 2), null, null);
    }

    private static void create(BoundedProblemHttp a, String label, int actor, String title) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.send(label, "POST", actor, "/api/work-orders", json("facilityId", 10, "title", title));
        a.slice.expectStatus(label, response, 201);
        long id = a.identifier(label, response);
        if (id < 0 || before.has("work_order", id)) { a.slice.check(label + " new identity", false, "id=" + id); return; }
        var expected = before.add("work_order", of("id", id, "facility_id", 10L, "reporter_id", (long) actor, "title", title,
                "assignee", null, "note", null, "removed", 0L, "version", 0L));
        a.expected(label, expected, a.fixture.snapshot());
        a.row(label, response, 201, WORK, id);
    }

    static void details(BoundedProblemHttp a) throws Exception {
        a.getRow("D01 manager reads", 1, path(102), WORK, 102);
        a.refuse("D01 reporter cannot read", "GET", 4, path(102), "", 403, "Forbidden");
        for (long id : List.of(103L, 999999L)) a.refuse("D01 hidden or absent " + id, "GET", 1, path(id), "", 404, "WorkOrderNotFound");
    }

    static void edits(BoundedProblemHttp a) throws Exception {
        edit(a, "P01 note only", 102, 2, "{\"note\":\"fixed\"}", "note", "fixed");
        a.getRow("P01 read-back", 1, path(102), WORK, 102);
        edit(a, "P02 clear nullable fields", 102, 3, "{\"assignee\":null,\"note\":null}", "assignee", null, "note", null);
        a.getRow("P02 clear read-back", 1, path(102), WORK, 102);
        edit(a, "P03 assign and rename", 102, 4, "{\"assignee\":2,\"title\":\"Renamed\"}", "assignee", 2L, "title", "Renamed");
        edit(a, "P03 same-value change increments version", 102, 5, "{\"title\":\"Renamed\"}", "title", "Renamed");
        a.refuse("P04 stale", "PATCH", 1, path(102), patchBody(2, "{\"note\":\"stale\"}"), 409, "StaleVersion");
        a.refuse("P04 permission before stale", "PATCH", 2, path(102), patchBody(2, "{\"note\":\"stale\"}"), 403, "Forbidden");
        a.refuse("P04 empty changes", "PATCH", 1, path(102), patchBody(6, "{}"), 400, "EmptyChange");
        a.refuse("P04 absent row", "PATCH", 1, path(999999), patchBody(0, "{\"note\":\"x\"}"), 404, "WorkOrderNotFound");
        invalidEdits(a);
        var before = a.fixture.snapshot();
        String body = "{\"id\":101,\"expectedVersion\":6,\"changes\":{\"note\":\"path wins\"}}";
        var response = a.send("P06 path identity wins", "PATCH", 1, path(102), body);
        a.expected("P06", before.with("work_order", 102, "note", "path wins", "version", 7L), a.fixture.snapshot());
        a.row("P06", response, 200, WORK, 102);
        edit(a, "P07 soft delete", 101, 0, "{\"removed\":true}", "removed", 1L);
        a.refuse("P07 removed detail", "GET", 1, path(101), "", 404, "WorkOrderNotFound");
        a.refuse("P07 removed cannot restore", "PATCH", 1, path(101), patchBody(1, "{\"removed\":false}"), 404, "WorkOrderNotFound");
        a.page("P07 scope also hides list row", 1, WORKS, WORK, List.of(102L, 105L, 106L), 3, 1, 20);
        edit(a, "P08 empty note", 102, 7, "{\"note\":\"\"}", "note", "");
        edit(a, "P08 500-character note", 102, 8, json("note", "n".repeat(500)), "note", "n".repeat(500));
        for (String field : List.of("facility", "reporter", "version")) a.invalidRequest("P08 forbidden change " + field, "PATCH", 1, path(102), patchBody(9, json(field, 2)), null, null);
    }

    private static void invalidEdits(BoundedProblemHttp a) throws Exception {
        for (String title : List.of("null", "\"   \"", "\"" + "x".repeat(101) + "\"")) {
            a.invalidRequest("P05 title", "PATCH", 1, path(102), patchBody(6, "{\"title\":" + title + "}"), "changes.title", title.length() < 20 ? "notBlank" : "length");
        }
        a.invalidRequest("P05 long note", "PATCH", 1, path(102), patchBody(6, json("note", "n".repeat(501))), "changes.note", "length");
        a.invalidRequest("P05 omitted version", "PATCH", 1, path(102), "{\"changes\":{\"note\":\"x\"}}", "expectedVersion", "NotNull");
        a.invalidRequest("P05 null version", "PATCH", 1, path(102), "{\"expectedVersion\":null,\"changes\":{\"note\":\"x\"}}", "expectedVersion", "NotNull");
    }

    private static void edit(BoundedProblemHttp a, String label, long id, long version, String changes, Object... pairs) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.send(label, "PATCH", 1, path(id), patchBody(version, changes));
        var expected = before.with("work_order", id, pairs).with("work_order", id, "version", version + 1);
        a.expected(label, expected, a.fixture.snapshot());
        a.row(label, response, 200, WORK, id);
    }

    static void lists(BoundedProblemHttp a) throws Exception {
        a.page("L01 all including unassigned", 1, WORKS, WORK, List.of(101L, 102L, 105L, 106L), 4, 1, 20);
        a.page("L01 assignee 3", 1, WORKS + "?assignee=3", WORK, List.of(102L, 105L), 2, 1, 20);
        a.page("L01 assignee 2", 1, WORKS + "?assignee=2", WORK, List.of(106L), 1, 1, 20);
        a.page("L01 unknown assignee is empty", 1, WORKS + "?assignee=999999", WORK, List.of(), 0, 1, 20);
        for (int page = 1; page <= 3; page++) a.page("L02 page " + page, 1, WORKS + "?size=2&page=" + page, WORK,
                page == 1 ? List.of(101L, 102L) : page == 2 ? List.of(105L, 106L) : List.of(), 4, page, 2);
        a.refuse("L03 forbidden", "GET", 2, WORKS, "", 403, "Forbidden");
        a.refuse("L03 unknown facility", "GET", 1, "/api/facilities/999999/work-orders", "", 404, "FacilityNotFound");
        a.page("L03 empty facility", 1, "/api/facilities/12/work-orders", WORK, List.of(), 0, 1, 20);
        pageDefaults(a, "/api/facilities", 0, FACILITY, List.of(10L, 12L, 11L));
        pageDefaults(a, WORKS, 1, WORK, List.of(101L, 102L, 105L, 106L));
        pageDefaults(a, INSPECTIONS, 1, INSPECTION, List.of(201L, 202L, 204L, 203L, 206L));
        pageDefaults(a, "/api/facilities/12/work-orders", 1, WORK, List.of());
        for (int page = 1; page <= 4; page++) {
            List<Long> ids = List.of(201L, 202L, 204L, 203L, 206L);
            int from = Math.min((page - 1) * 2, ids.size()), until = Math.min(page * 2, ids.size());
            a.page("L05 inspection page " + page, 1, INSPECTIONS + "?size=2&page=" + page, INSPECTION, ids.subList(from, until), 5, page, 2);
        }
        a.page("L05 other manager", 2, "/api/facilities/11/inspections", INSPECTION, List.of(205L), 1, 1, 20);
        a.page("L05 empty", 1, "/api/facilities/12/inspections", INSPECTION, List.of(), 0, 1, 20);
    }

    private static void pageDefaults(BoundedProblemHttp a, String path, int actor, BoundedProblemHttp.Projection projection, List<Long> ids) throws Exception {
        a.badPages("L04 " + path, actor, path);
        a.page("L04 defaults " + path, actor, path, projection, ids, ids.size(), 1, 20);
        a.page("L04 default page " + path, actor, path + "?size=1", projection, ids.isEmpty() ? List.of() : ids.subList(0, 1), ids.size(), 1, 1);
        a.page("L04 default size " + path, actor, path + "?page=2", projection, List.of(), ids.size(), 2, 20);
        a.page("L04 maximum " + path, actor, path + "?page=10000&size=100", projection, List.of(), ids.size(), 10000, 100);
    }

    static List<Long> workIds(BoundedProblemState s, long facility, Long assignee) {
        return s.table("work_order").stream().filter(r -> r.number("facility_id") == facility && r.number("removed") == 0)
                .filter(r -> assignee == null || assignee.equals(r.value("assignee"))).map(r -> r.number("id")).sorted().toList();
    }
    static List<Long> inspectionIds(BoundedProblemState s, long facility) {
        return s.table("inspection_order").stream().filter(r -> r.number("facility_id") == facility)
                .sorted(Comparator.comparing((BoundedProblemState.Row r) -> (java.time.Instant) r.value("due_at")).thenComparingLong(r -> r.number("id")))
                .map(r -> r.number("id")).toList();
    }
}
