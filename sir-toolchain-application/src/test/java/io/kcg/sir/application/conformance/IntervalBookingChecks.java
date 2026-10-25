package io.kcg.sir.application.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Additional checks in the existing isolated MySQL/application; no second application or test hooks. */
final class IntervalBookingChecks {
    private static final String PATH = "/api/bookings";
    private IntervalBookingChecks() {}

    static void verify(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var tasks = new ArrayList<Callable<HttpAssertionClient.Response>>();
        for (int i=0; i<8; i++) tasks.add(() -> book(s, http, PATH, 1, "10:00", "11:00"));
        List<HttpAssertionClient.Response> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var future : pool.invokeAll(tasks)) results.add(future.get(30, TimeUnit.SECONDS));
        }
        s.check("room identity lock admits only one of eight overlapping intervals",
                results.stream().filter(r -> r.statusCode() == 201).count() == 1
                        && results.stream().allMatch(r -> r.statusCode() == 201 || r.statusCode() == 409),
                results.stream().map(HttpAssertionClient.Response::statusCode).toList().toString());
        s.check("only the committed interval has a stored row", s.rowsOf(observer, "booking").size() == 1,
                s.rowsOf(observer, "booking").toString());
        expect(s, book(s, http, PATH, 1, "11:00", "12:00"), 201, "touching half-open interval is allowed");
        expect(s, book(s, http, PATH, 1, "09:00", "10:00"), 201, "left adjacent interval is allowed");
        var before = s.rowsOf(observer, "booking");
        for (String[] range : List.of(new String[]{"10:15", "10:45"}, new String[]{"08:00", "13:00"},
                new String[]{"09:30", "10:30"}, new String[]{"11:30", "12:30"}))
            expect(s, book(s, http, PATH, 1, range[0], range[1]), 409, "all overlap directions are refused");
        for (String[] range : List.of(new String[]{"12:00", "12:00"}, new String[]{"13:00", "12:00"}))
            expect(s, book(s, http, PATH, 1, range[0], range[1]), 400, "empty or reversed interval is refused");
        expect(s, book(s, http, PATH, 999, "10:00", "11:00"), 404, "missing lock anchor follows declared not-found");
        s.check("refused intervals never mutate the booking rows", before.equals(s.rowsOf(observer, "booking")),
                s.rowsOf(observer, "booking").toString());
        expect(s, book(s, http, PATH, 2, "10:00", "11:00"), 201, "another room has an independent interval domain");
        priorReadWaitsForLockAndSeesCommit(s, observer, http);
        weeklySeries(s, observer, http);
    }

    /** A weekly series on room 2 books the same hours seven days apart; one taken week or an over-long series books nothing. */
    private static void weeklySeries(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        long before = roomRows(s, observer, "2");
        expect(s, weekly(s, http, "2030-01-01", 3), 201, "a three-week series is booked");
        s.check("one booking per week, seven days apart", roomRows(s, observer, "2") == before + 3
                && s.fixtureScalar("SELECT COUNT(*) FROM booking WHERE room_id=2 AND starts_at IN ('2030-01-01 14:00:00','2030-01-08 14:00:00','2030-01-15 14:00:00')") == 3,
                s.rowsOf(observer, "booking").toString());
        var rows = s.rowsOf(observer, "booking");
        expect(s, weekly(s, http, "2029-12-25", 3), 409, "a series whose second week is taken is refused");
        s.check("the first week of the refused series is rolled back", rows.equals(s.rowsOf(observer, "booking")),
                s.rowsOf(observer, "booking").toString());
        expect(s, weekly(s, http, "2030-03-01", 13), 409, "a series over the bound is refused before any week");
        s.check("the over-long series wrote nothing", rows.equals(s.rowsOf(observer, "booking")), s.rowsOf(observer, "booking").toString());
    }

    private static long roomRows(BusinessSliceHarness s, MysqlObserver observer, String room) throws Exception {
        return s.rowsOf(observer, "booking").stream().filter(row -> row.split("\\|", -1)[1].equals(room)).count();
    }

    private static HttpAssertionClient.Response weekly(BusinessSliceHarness s, HttpAssertionClient http, String day, int weeks) throws Exception {
        return http.postJson(s.baseUrl() + PATH + "/weekly", Map.of(), "{\"roomId\":2,\"startsAt\":\"" + day
                + "T14:00:00Z\",\"endsAt\":\"" + day + "T15:00:00Z\",\"weeks\":" + weeks + "}");
    }

    private static void priorReadWaitsForLockAndSeesCommit(BusinessSliceHarness s, MysqlObserver observer,
            HttpAssertionClient http) throws Exception {
        // Hold the anchor ourselves. The HTTP transaction first reads an empty Booking result, then waits.
        // Insert an overlap only AFTER the wait is observable. A stale InnoDB snapshot or MyBatis cache
        // would now admit the second row despite the acquired Room lock.
        try (var pool = Executors.newSingleThreadExecutor()) {
            s.fixtureStatement("START TRANSACTION");
            try {
                s.fixtureStatement("SELECT id FROM room WHERE id=3 FOR UPDATE");
                var request = pool.submit(() -> book(s, http, PATH + "/after-read", 3, "10:00", "11:00"));
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    if (s.fixtureScalar("SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID "
                            + "AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='room'") > 0) {
                        waiting = true; break;
                    }
                    Thread.sleep(50);
                }
                s.check("after-read HTTP transaction demonstrably waits on its room lock", waiting, "performance_schema.data_lock_waits");
                s.fixtureStatement("INSERT INTO booking(room_id,starts_at,ends_at) VALUES(3,'2030-01-01 10:00:00','2030-01-01 11:00:00')");
                s.fixtureStatement("COMMIT");
                expect(s, request.get(30, TimeUnit.SECONDS), 409,
                        "after a prior identical read and lock wait, committed overlap is rechecked");
                s.check("stale pre-lock query never admits a second booking for room 3",
                        s.rowsOf(observer, "booking").stream().filter(row -> row.split("\\|", -1)[1].equals("3")).count() == 1,
                        s.rowsOf(observer, "booking").toString());
            } finally {
                s.fixtureStatement("ROLLBACK");
            }
        }
    }

    private static HttpAssertionClient.Response book(BusinessSliceHarness s, HttpAssertionClient http, String path,
            int room, String start, String end) throws Exception {
        return http.postJson(s.baseUrl() + path, Map.of(), "{\"roomId\":" + room
                + ",\"startsAt\":\"2030-01-01T" + start + ":00Z\",\"endsAt\":\"2030-01-01T" + end + ":00Z\"}");
    }
    private static void expect(BusinessSliceHarness s, HttpAssertionClient.Response response, int status, String reason) {
        s.expectStatus(reason, response, status);
    }
}
