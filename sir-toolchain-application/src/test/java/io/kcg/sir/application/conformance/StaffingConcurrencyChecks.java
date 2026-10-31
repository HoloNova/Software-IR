package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.StaffingRosterChecks.*;
import static io.kcg.sir.application.conformance.BoundedProblemState.Row.of;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

final class StaffingConcurrencyChecks {
    private StaffingConcurrencyChecks() { }

    static void sameEmployee(BoundedProblemHttp a) throws Exception {
        var before = a.fixture.snapshot();
        var calls = IntStream.range(0, 6).mapToObj(i -> new BoundedProblemRace.Call("POST", 1, assignments(203), "{\"employeeId\":103}")).toList();
        var results = BoundedProblemRace.run(a, "X01 same employee", "shift", 203, calls);
        var winners = results.stream().filter(r -> r.response().statusCode() == 201).toList();
        var losers = results.stream().filter(r -> r.response().statusCode() == 409).toList();
        a.slice.check("X01 one created and five already assigned", winners.size() == 1 && losers.size() == 5, results.toString());
        for (var loser : losers) a.error("X01 loser", loser.response(), 409, "AlreadyAssigned");
        if (winners.size() != 1) return;
        long id = a.identifier("X01 winner", winners.getFirst().response());
        if (id <= 0 || before.has("assignment", id)) { a.slice.check("X01 new identity", false, "id=" + id); return; }
        a.expected("X01 only one relation", before.add("assignment", of("id", id, "employee_id", 103L, "shift_id", 203L)), a.fixture.snapshot());
        a.row("X01 winner projection", winners.getFirst().response(), 201, ASSIGNMENT, id);
        a.integer("X01 count", 1, headcount(203), 1);
        readRoster(a, "X01 roster", 103);
        recompute(a, "X01 invariant");
    }

    static void differentEmployees(BoundedProblemHttp a) throws Exception {
        var before = a.fixture.snapshot();
        var calls = IntStream.rangeClosed(101, 103).mapToObj(employee -> new BoundedProblemRace.Call("POST", 1, assignments(203), "{\"employeeId\":" + employee + "}")).toList();
        var results = BoundedProblemRace.run(a, "X02 different employees", "shift", 203, calls);
        var expected = before;
        for (var result : results) {
            a.slice.expectStatus("X02 each employee succeeds", result.response(), 201);
            long id = a.identifier("X02 winner", result.response());
            if (id <= 0 || expected.has("assignment", id)) { a.slice.check("X02 distinct new identity", false, "id=" + id); return; }
            long employee = BoundedProblemHttp.decode(result.call().body()).path("employeeId").longValue();
            expected = expected.add("assignment", of("id", id, "employee_id", employee, "shift_id", 203L));
            a.row("X02 stored projection", result.response(), 201, ASSIGNMENT, id);
        }
        a.expected("X02 exactly three independent employees", expected, a.fixture.snapshot());
        a.integer("X02 three headcount", 1, headcount(203), 3);
        recompute(a, "X02 invariant");
    }

    static void sameWeeklyPlan(BoundedProblemHttp a) throws Exception {
        var before = a.fixture.snapshot();
        var calls = IntStream.range(0, 6).mapToObj(i -> new BoundedProblemRace.Call("POST", 1, StaffingWeeklyChecks.path(12), StaffingWeeklyChecks.PLAN)).toList();
        var results = BoundedProblemRace.run(a, "X03 same weekly plan", "store", 12, calls);
        var winners = results.stream().filter(r -> r.response().statusCode() == 201).toList();
        var losers = results.stream().filter(r -> r.response().statusCode() == 409).toList();
        a.slice.check("X03 exactly one whole plan and five conflicts", winners.size() == 1 && losers.size() == 5, results.toString());
        for (var winner : winners) a.empty("X03 winner", winner.response(), 201);
        for (var loser : losers) a.error("X03 loser", loser.response(), 409, "ShiftExists");
        var after = a.fixture.snapshot();
        var expected = StaffingWeeklyChecks.generatedState(a, "X03", before, after, LocalDate.parse(StaffingWeeklyChecks.FIRST), "OPENING", 3);
        if (expected != null) a.expected("X03 no partial or extra plans and no assignments", expected, after);
        var ids = shiftIds(after, 12);
        a.page("X03 exact shifts read-back", 1, shifts(12), SHIFT, ids, 3, 1, 20);
        recompute(a, "X03 invariant");
    }
}
