package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.StaffingRosterChecks.*;
import static io.kcg.sir.application.conformance.BoundedProblemState.Row.of;

import java.util.List;

final class StaffingAssignmentChecks {
    private StaffingAssignmentChecks() { }

    static void assignmentsAndRefusals(BoundedProblemHttp a) throws Exception {
        assign(a, "A01 employee is not the actor", 103, 203, json("employeeId", 103));
        a.integer("A01 count", 1, headcount(203), 1);
        readRoster(a, "A01 roster", 103);
        a.refuse("A02 duplicate", "POST", 1, assignments(203), json("employeeId", 103), 409, "AlreadyAssigned");
        a.integer("A02 count remains one", 1, headcount(203), 1);
        assign(a, "A03 employee 101", 101, 203, json("employeeId", 101));
        assign(a, "A03 employee 102", 102, 203, json("employeeId", 102));
        a.integer("A03 three employees no capacity restriction", 1, headcount(203), 3);
        a.refuse("A04 shift first", "POST", 1, assignments(999999), json("employeeId", 103), 404, "ShiftNotFound");
        a.refuse("A04 unknown employee", "POST", 1, assignments(203), json("employeeId", 999999), 404, "EmployeeNotFound");
        a.refuse("A04 permission before employee", "POST", 2, assignments(203), json("employeeId", 999999), 403, "Forbidden");
        a.refuse("A04 wrong store", "POST", 1, assignments(203), json("employeeId", 104), 409, "WrongStore");
        a.invalidRequest("A05 missing employee", "POST", 1, assignments(203), "{}", "employeeId", "NotNull");
        a.invalidRequest("A05 null employee", "POST", 1, assignments(203), "{\"employeeId\":null}", "employeeId", "NotNull");
        for (String field : List.of("employee", "manager")) a.invalidRequest("A05 forged " + field, "POST", 1, assignments(203), json("employeeId", 103, field, 1), null, null);
        recompute(a, "A01-A05 invariants");
    }

    static void sameDayBands(BoundedProblemHttp a) throws Exception {
        long id = assign(a, "A06 same employee two bands", 102, 202, json("employeeId", 102));
        a.integer("A06 first shift unchanged", 1, headcount(201), 2);
        a.integer("A06 second shift now two", 1, headcount(202), 2);
        if (id > 0) a.page("A06 roster in relation id order", 1, roster(102), ASSIGNMENT, List.of(302L, id), 2, 1, 20);
        recompute(a, "A06 invariant");
    }

    static void pathIdentity(BoundedProblemHttp a) throws Exception {
        assign(a, "A07 path shift wins", 103, 203, json("shiftId", 201, "employeeId", 103));
        a.integer("A07 target shift", 1, headcount(203), 1);
        a.integer("A07 forged shift unchanged", 1, headcount(201), 2);
    }

    static void relationOrder(BoundedProblemHttp a) throws Exception {
        // TEST_FIXTURE_SQL: two valid relations intentionally reverse the shift-day order, before the GET.
        a.fixture.statement("INSERT INTO assignment (id,employee_id,shift_id) VALUES (900,101,206),(901,101,203)");
        a.page("A08 sort relations not shift days", 1, roster(101), ASSIGNMENT, List.of(301L, 303L, 900L, 901L), 4, 1, 20);
        a.integer("A08 shift 206", 1, headcount(206), 1);
        a.integer("A08 shift 203", 1, headcount(203), 1);
        recompute(a, "A08 invariant");
    }

    static long assign(BoundedProblemHttp a, String label, long employee, long shift, String body) throws Exception {
        var before = a.fixture.snapshot();
        var response = a.send(label, "POST", 1, assignments(shift), body);
        a.slice.expectStatus(label, response, 201);
        long id = a.identifier(label, response);
        if (id < 0 || before.has("assignment", id)) { a.slice.check(label + " new identity", false, "id=" + id); return -1; }
        a.expected(label, before.add("assignment", of("id", id, "employee_id", employee, "shift_id", shift)), a.fixture.snapshot());
        a.row(label, response, 201, ASSIGNMENT, id);
        return id;
    }
}
