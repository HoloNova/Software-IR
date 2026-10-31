package io.kcg.sir.application.conformance;

import static io.kcg.sir.application.conformance.MaintenanceWorkOrderChecks.*;
import static io.kcg.sir.application.conformance.MaintenanceInspectionChecks.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

final class MaintenanceConcurrencyChecks {
    private MaintenanceConcurrencyChecks() { }

    static void sameVersion(BoundedProblemHttp a) throws Exception {
        var before = a.fixture.snapshot();
        var calls = IntStream.rangeClosed(1, 6).mapToObj(i -> new BoundedProblemRace.Call("PATCH", 1, path(105),
                patchBody(0, "{\"note\":\"race-" + i + "\"}"))).toList();
        var results = BoundedProblemRace.run(a, "X01 same version", "work_order", 105, calls);
        var winners = results.stream().filter(r -> r.response().statusCode() == 200).toList();
        var losers = results.stream().filter(r -> r.response().statusCode() == 409).toList();
        a.slice.check("X01 exactly one winner and five stale versions", winners.size() == 1 && losers.size() == 5, results.toString());
        for (var loser : losers) a.error("X01 loser", loser.response(), 409, "StaleVersion");
        if (winners.size() != 1) return;
        var winner = winners.getFirst();
        String note = BoundedProblemHttp.decode(winner.call().body()).path("changes").path("note").textValue();
        a.expected("X01 only winner changes 105", before.with("work_order", 105, "note", note, "version", 1L), a.fixture.snapshot());
        a.row("X01 winner", winner.response(), 200, WORK, 105);
        a.getRow("X01 read-back", 1, path(105), WORK, 105);
    }

    static void concurrentClosures(BoundedProblemHttp a) throws Exception {
        var before = a.fixture.snapshot();
        String body = json("cutoff", CUTOFF);
        var calls = IntStream.range(0, 6).mapToObj(i -> new BoundedProblemRace.Call("POST", 1, closePath(10), body)).toList();
        var results = BoundedProblemRace.run(a, "X02 concurrent closures", "facility", 10, calls);
        for (var result : results) a.empty("X02 every closure succeeds", result.response(), 200);
        a.expected("X02 strict set closed once", closedState(before, 10, Instant.parse(CUTOFF)), a.fixture.snapshot());
    }

    static void generationVersusClosure(BoundedProblemHttp a) throws Exception {
        var before = a.fixture.snapshot();
        String first = "2000-01-01T00:00:00Z", cutoff = "2000-01-04T00:00:00Z";
        var calls = List.of(new BoundedProblemRace.Call("POST", 1, generatePath(10), plan("Routine", first, 1, 3)),
                new BoundedProblemRace.Call("POST", 1, closePath(10), json("cutoff", cutoff)));
        var results = BoundedProblemRace.run(a, "X03 serialize whole batches", "facility", 10, calls);
        a.empty("X03 generation", results.get(0).response(), 201);
        a.empty("X03 closure", results.get(1).response(), 200);
        var after = a.fixture.snapshot();
        var oldClosed = closedState(before, 10, Instant.parse(cutoff));
        var allOpen = generatedState(a, "X03", oldClosed, after, 10, "Routine", Instant.parse(first), 1, 3, 0);
        if (allOpen == null) return;
        var allClosed = allOpen;
        for (var row : after.addedSince("inspection_order", before)) allClosed = allClosed.with("inspection_order", row.number("id"), "closed", 1L);
        a.slice.check("X03 exact state equals one of the two complete serial orders", after.equals(allOpen) || after.equals(allClosed),
                "allOpen=" + allOpen + "; allClosed=" + allClosed + "; actual=" + after);
    }
}
