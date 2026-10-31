package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.BoundedProblemState.Row.of;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** B/Q and independent staffing invariants. References are scalar identifiers, never nested entity JSON. */
final class StaffingRosterChecks {
    static final BoundedProblemHttp.Projection STORE = new BoundedProblemHttp.Projection("store", Map.of("id", "id", "name", "name"), Set.of());
    static final BoundedProblemHttp.Projection EMPLOYEE = new BoundedProblemHttp.Projection("employee", Map.of("id", "id", "store", "store_id", "name", "name"), Set.of());
    static final BoundedProblemHttp.Projection SHIFT = new BoundedProblemHttp.Projection("shift", Map.of("id", "id", "store", "store_id", "day", "day", "band", "band"), Set.of());
    static final BoundedProblemHttp.Projection ASSIGNMENT = new BoundedProblemHttp.Projection("assignment", Map.of("id", "id", "employee", "employee_id", "shift", "shift_id"), Set.of());
    private record Endpoint(String method, String path, String body) { }
    private StaffingRosterChecks() { }
    static String json(Object... pairs) throws Exception { return BoundedProblemHttp.json(of(pairs).cells()); }
    static String shifts(long store) { return "/api/stores/" + store + "/shifts"; }
    static String employees(long store) { return "/api/stores/" + store + "/employees"; }
    static String assignments(long shift) { return "/api/shifts/" + shift + "/assignments"; }
    static String headcount(long shift) { return "/api/shifts/" + shift + "/headcount"; }
    static String roster(long employee) { return "/api/employees/" + employee + "/roster"; }

    static boolean validSeed(BoundedProblemState s) {
        return s.table("access_token").stream().allMatch(r -> s.has("user", r.number("owner_id")))
                && s.table("store").stream().allMatch(r -> s.has("user", r.number("manager_id")))
                && s.table("employee").stream().allMatch(r -> s.has("store", r.number("store_id")))
                && s.table("shift").stream().allMatch(r -> s.has("store", r.number("store_id")))
                && validRelations(s) && uniqueShifts(s) && rosterIds(s, 101).equals(List.of(301L, 303L));
    }
    static boolean validRelations(BoundedProblemState s) {
        var pairs = s.table("assignment").stream().map(r -> r.number("employee_id") + "/" + r.number("shift_id")).toList();
        return pairs.size() == Set.copyOf(pairs).size() && s.table("assignment").stream().allMatch(r ->
                s.has("employee", r.number("employee_id")) && s.has("shift", r.number("shift_id"))
                        && s.row("employee", r.number("employee_id")).number("store_id") == s.row("shift", r.number("shift_id")).number("store_id"));
    }
    static boolean uniqueShifts(BoundedProblemState s) {
        var keys = s.table("shift").stream().map(r -> r.number("store_id") + "/" + r.value("day") + "/" + r.text("band")).toList();
        return keys.size() == Set.copyOf(keys).size();
    }

    static void identity(BoundedProblemHttp a) throws Exception {
        a.page("B01 anonymous stores", 0, "/api/stores", STORE, List.of(12L, 10L, 11L), 3, 1, 20);
        a.page("B01 first page", 0, "/api/stores?size=2", STORE, List.of(12L, 10L), 3, 1, 2);
        a.page("B01 second page", 0, "/api/stores?size=2&page=2", STORE, List.of(11L), 3, 2, 2);
        var endpoints = List.of(new Endpoint("POST", StaffingWeeklyChecks.path(12), StaffingWeeklyChecks.PLAN),
                new Endpoint("GET", shifts(10), ""), new Endpoint("GET", employees(10), ""),
                new Endpoint("POST", assignments(203), "{\"employeeId\":103}"),
                new Endpoint("GET", headcount(201), ""), new Endpoint("GET", roster(101), ""));
        for (var endpoint : endpoints) for (var actor : a.invalidIdentities()) {
            a.unauthorized("B02 " + endpoint.method() + " " + endpoint.path(), endpoint.method(), actor, endpoint.path(), endpoint.body());
        }
    }

    static void listings(BoundedProblemHttp a) throws Exception {
        List<Long> shifts = List.of(201L, 202L, 203L, 205L, 206L);
        for (int page = 1; page <= 4; page++) {
            int from = Math.min((page - 1) * 2, shifts.size()), until = Math.min(page * 2, shifts.size());
            a.page("Q01 shift page " + page, 1, shifts(10) + "?size=2&page=" + page, SHIFT, shifts.subList(from, until), 5, page, 2);
        }
        a.page("Q01 other store", 2, shifts(11), SHIFT, List.of(204L), 1, 1, 20);
        a.page("Q01 empty store", 1, shifts(12), SHIFT, List.of(), 0, 1, 20);
        a.page("Q02 employees first", 1, employees(10) + "?size=2", EMPLOYEE, List.of(101L, 102L), 3, 1, 2);
        a.page("Q02 employees second", 1, employees(10) + "?size=2&page=2", EMPLOYEE, List.of(103L), 3, 2, 2);
        a.page("Q02 empty employees", 1, employees(12), EMPLOYEE, List.of(), 0, 1, 20);
        for (int page = 1; page <= 3; page++) a.page("Q03 roster page " + page, 1, roster(101) + "?size=1&page=" + page,
                ASSIGNMENT, page == 1 ? List.of(301L) : page == 2 ? List.of(303L) : List.of(), 2, page, 1);
        a.page("Q03 employee 102", 1, roster(102), ASSIGNMENT, List.of(302L), 1, 1, 20);
        a.page("Q03 unassigned employee", 1, roster(103), ASSIGNMENT, List.of(), 0, 1, 20);
        a.page("Q03 other manager's employee", 2, roster(104), ASSIGNMENT, List.of(304L), 1, 1, 20);
        defaults(a, "/api/stores", 0, STORE, List.of(12L, 10L, 11L));
        defaults(a, shifts(10), 1, SHIFT, shifts);
        defaults(a, employees(10), 1, EMPLOYEE, List.of(101L, 102L, 103L));
        defaults(a, roster(101), 1, ASSIGNMENT, List.of(301L, 303L));
        recompute(a, "Q05 seeded headcounts");
        permissions(a);
    }

    private static void defaults(BoundedProblemHttp a, String path, int actor, BoundedProblemHttp.Projection view, List<Long> ids) throws Exception {
        a.badPages("Q04 " + path, actor, path);
        a.page("Q04 both defaults " + path, actor, path, view, ids, ids.size(), 1, 20);
        a.page("Q04 page default " + path, actor, path + "?size=1", view, ids.subList(0, 1), ids.size(), 1, 1);
        a.page("Q04 size default " + path, actor, path + "?page=2", view, List.of(), ids.size(), 2, 20);
        a.page("Q04 inclusive maxima " + path, actor, path + "?page=10000&size=100", view, List.of(), ids.size(), 10000, 100);
    }

    private static void permissions(BoundedProblemHttp a) throws Exception {
        for (String path : List.of(shifts(10), employees(10), headcount(201), roster(101))) {
            a.refuse("Q06 forbidden " + path, "GET", 2, path, "", 403, "Forbidden");
        }
        a.refuse("Q06 unknown shifts store", "GET", 1, shifts(999999), "", 404, "StoreNotFound");
        a.refuse("Q06 unknown employees store", "GET", 1, employees(999999), "", 404, "StoreNotFound");
        a.refuse("Q06 unknown headcount shift", "GET", 1, headcount(999999), "", 404, "ShiftNotFound");
        a.refuse("Q06 unknown roster employee", "GET", 1, roster(999999), "", 404, "EmployeeNotFound");
    }

    static List<Long> shiftIds(BoundedProblemState s, long store) {
        return s.table("shift").stream().filter(r -> r.number("store_id") == store)
                .sorted(Comparator.comparing((BoundedProblemState.Row r) -> (java.time.LocalDate) r.value("day")).thenComparingLong(r -> r.number("id")))
                .map(r -> r.number("id")).toList();
    }
    static List<Long> rosterIds(BoundedProblemState s, long employee) {
        return s.table("assignment").stream().filter(r -> r.number("employee_id") == employee).map(r -> r.number("id")).sorted().toList();
    }
    static void readRoster(BoundedProblemHttp a, String label, long employee) throws Exception {
        var ids = rosterIds(a.fixture.snapshot(), employee);
        a.page(label, employee == 104 ? 2 : 1, roster(employee), ASSIGNMENT, ids, ids.size(), 1, 20);
    }
    static void recompute(BoundedProblemHttp a, String label) throws Exception {
        var state = a.fixture.snapshot();
        a.slice.check(label + " same-store foreign keys and unique employee/shift pairs", validRelations(state), "all assignment rows");
        a.slice.check(label + " unique store/day/band", uniqueShifts(state), "all shift rows");
        for (var shift : state.table("shift")) {
            long count = state.table("assignment").stream().filter(r -> r.number("shift_id") == shift.number("id")).count();
            a.integer(label + " shift=" + shift.number("id"), shift.number("store_id") == 11 ? 2 : 1, headcount(shift.number("id")), count);
        }
    }
}
