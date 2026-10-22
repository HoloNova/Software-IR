package io.kcg.sir.application.conformance;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Independent HTTP and observer-connection checks; fixture control writes are explicitly labelled. */
final class LibraryBorrowingChecks {
    private static final int LIMIT = 5;
    private static final int USER_COUNTER = 2, LOAN_READER = 1, LOAN_COPY = 2, LOAN_RETURNED = 6;
    private LibraryBorrowingChecks() {}

    /** Fixed expectations checked against the actual SIR declarations, never inferred from a response. */
    enum Failure {
        FORBIDDEN(403, "Forbidden"),
        BORROW_LIMIT_EXCEEDED(409, "BorrowLimitExceeded"),
        ALREADY_RETURNED(409, "AlreadyReturned"),
        COPY_NOT_AVAILABLE(409, "CopyNotAvailable"),
        LOAN_COUNTER_MISMATCH(409, "LoanCounterMismatch"),
        NOT_RENEWABLE(409, "NotRenewable");

        private final int status;
        private final String code;
        Failure(int status, String code) { this.status = status; this.code = code; }
        int status() { return status; }
        String code() { return code; }
    }

    static void verify(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        invariant(s, observer, "seed counters match their active loans");
        refusalWithoutWrites(s, observer, http);
        lastSlotAndReturn(s, observer, http);
        independentReadersCompeteForOneCopy(s, observer, http);
        rollbackAndUninitialisedCounter(s, observer, http);
        renewalDoesNotConsumeASlot(s, observer, http);
        invariant(s, observer, "all final counters match active loans and stay within the limit");
    }

    private static void refusalWithoutWrites(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var before = snapshot(s, observer);
        status(s, "anonymous borrow is refused", http.postJson(s.baseUrl() + "/api/loans", Map.of(), "{\"barcode\":\"C20\"}"), 401);
        status(s, "missing copy is refused before reserving a slot", borrow(s, http, 2, 999), 404);
        error(s, "another reader cannot return this loan", giveBack(s, http, 2, 1), Failure.FORBIDDEN);
        status(s, "a missing loan is not a successful return", giveBack(s, http, 2, 999999), 404);
        unchanged(s, observer, before, "authentication, ownership and not-found refusals leave all three tables unchanged");
    }

    private static void lastSlotAndReturn(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var tasks = new ArrayList<Callable<HttpAssertionClient.Response>>();
        for (int id = 5; id <= 12; id++) {
            final int copy = id;
            tasks.add(() -> borrow(s, http, 1, copy));
        }
        var responses = parallel(tasks);
        s.check("eight simultaneous borrows from four active loans admit exactly one last slot",
                responses.stream().filter(r -> r.statusCode() == 201).count() == 1
                        && responses.stream().allMatch(r -> r.statusCode() == 201 || r.statusCode() == 409), statuses(responses));
        for (var response : responses) if (response.statusCode() != 201)
            error(s, "remaining requests report the borrowing limit", response, Failure.BORROW_LIMIT_EXCEEDED);
        invariant(s, observer, "last-slot competition never loses an increment or exceeds five");
        s.check("reader reaches exactly five", counter(s, observer, 1) == LIMIT, snapshot(s, observer).users().toString());
        var winner = responses.stream().filter(r -> r.statusCode() == 201).findFirst();
        if (winner.isEmpty()) return; // The prerequisite has already been recorded as FAILED above.
        long loanId = Long.parseLong(BusinessSliceHarness.field(winner.get().body(), "id"));
        long copyId = Long.parseLong(parts(row(s.rowsOf(observer, "loan"), loanId))[LOAN_COPY]);
        concurrentReturn(s, observer, http, loanId, copyId);
        status(s, "a successful return frees a slot for another borrow", borrow(s, http, 1, 20), 201);
        status(s, "a different reader may borrow the returned copy", borrow(s, http, 2, copyId), 201);
        var before = snapshot(s, observer);
        error(s, "old return cannot free a copy borrowed under a new loan", giveBack(s, http, 1, loanId), Failure.ALREADY_RETURNED);
        unchanged(s, observer, before, "replaying an old return cannot change the new holder or either reader's counter");
        mixedBorrowAndReturn(s, observer, http);
    }

    private static void concurrentReturn(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http,
            long loanId, long copyId) throws Exception {
        var tasks = new ArrayList<Callable<HttpAssertionClient.Response>>();
        for (int i = 0; i < 8; i++) tasks.add(() -> giveBack(s, http, 1, loanId));
        var responses = parallel(tasks);
        s.check("eight simultaneous returns have exactly one success",
                responses.stream().filter(r -> r.statusCode() == 200).count() == 1
                        && responses.stream().allMatch(r -> r.statusCode() == 200 || r.statusCode() == 409), statuses(responses));
        for (var response : responses) if (response.statusCode() != 200)
            error(s, "replayed return has a stable declared failure", response, Failure.ALREADY_RETURNED);
        s.check("only the first return releases a slot", counter(s, observer, 1) == 4, snapshot(s, observer).users().toString());
        String[] loan = parts(row(s.rowsOf(observer, "loan"), loanId));
        s.check("return marker and timestamp are both persisted once", loan[LOAN_RETURNED].equals("1") && !loan[7].equals("null"), String.join("|", loan));
        s.check("returned copy is available", parts(row(s.rowsOf(observer, "copy"), copyId))[3].equals("AVAILABLE"), Long.toString(copyId));
        invariant(s, observer, "concurrent duplicate returns cannot underflow a counter");
    }

    private static void mixedBorrowAndReturn(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var responses = parallel(List.of(() -> giveBack(s, http, 1, 2), () -> borrow(s, http, 1, 13)));
        status(s, "concurrent return succeeds", responses.get(0), 200);
        var borrow = responses.get(1);
        s.check("borrow/return race follows either valid serial order", borrow.statusCode() == 201 || borrow.statusCode() == 409, statuses(responses));
        if (borrow.statusCode() == 409) error(s, "borrow before the return still sees a full reader", borrow, Failure.BORROW_LIMIT_EXCEEDED);
        s.check("counter reflects the actual mixed-race outcome", counter(s, observer, 1) == (borrow.statusCode() == 201 ? 5 : 4), snapshot(s, observer).users().toString());
        invariant(s, observer, "mixed borrow and return preserve ledger equality");
    }

    private static void independentReadersCompeteForOneCopy(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var responses = parallel(List.of(() -> borrow(s, http, 6, 60), () -> borrow(s, http, 7, 60)));
        s.check("two distinct reader locks still admit only one holder of a physical copy",
                responses.stream().filter(r -> r.statusCode() == 201).count() == 1
                        && responses.stream().filter(r -> r.statusCode() == 409).count() == 1, statuses(responses));
        for (var response : responses) if (response.statusCode() != 201)
            error(s, "losing copy claim follows the declared conflict", response, Failure.COPY_NOT_AVAILABLE);
        s.check("losing reader's earlier reserved slot rolls back", counter(s, observer, 6) + counter(s, observer, 7) == 1,
                snapshot(s, observer).users().toString());
        long loans = s.rowsOf(observer, "loan").stream().map(LibraryBorrowingChecks::parts).filter(r -> r[LOAN_COPY].equals("60") && active(r)).count();
        s.check("only one active loan exists for the shared copy", loans == 1, Long.toString(loans));
        invariant(s, observer, "copy contention does not drift either reader counter");
    }

    private static void rollbackAndUninitialisedCounter(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var before = snapshot(s, observer);
        error(s, "late occupied-copy failure rolls back the preceding slot increment", borrow(s, http, 2, 1), Failure.COPY_NOT_AVAILABLE);
        unchanged(s, observer, before, "borrow failure leaves counter, copy and loan rows exactly unchanged");
        // A fixture-only corruption models a legacy database whose new counter was never backfilled.
        s.fixtureStatement("UPDATE user SET active_loans=0 WHERE id=3");
        try {
            before = snapshot(s, observer);
            error(s, "uninitialised counter cannot be decremented below zero", giveBack(s, http, 3, 31), Failure.LOAN_COUNTER_MISMATCH);
            unchanged(s, observer, before, "invalid counter is refused, not silently repaired");
        } finally {
            s.fixtureStatement("UPDATE user SET active_loans=1 WHERE id=3");
        }
        s.fixtureStatement("UPDATE copy SET status='LOST' WHERE id=31");
        try {
            before = snapshot(s, observer);
            error(s, "late lost-copy failure rolls back both counter and loan return", giveBack(s, http, 3, 31), Failure.COPY_NOT_AVAILABLE);
            unchanged(s, observer, before, "failed return preserves the original counter, unreturned loan and lost-copy state");
        } finally {
            s.fixtureStatement("UPDATE copy SET status='BORROWED' WHERE id=31");
        }
    }

    private static void renewalDoesNotConsumeASlot(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        var users = s.rowsOf(observer, "user");
        status(s, "renewal of an active loan succeeds", http.postJson(s.baseUrl() + "/api/loans/51/renew", bearer(5), "{}"), 200);
        error(s, "a second renewal is refused", http.postJson(s.baseUrl() + "/api/loans/51/renew", bearer(5), "{}"), Failure.NOT_RENEWABLE);
        s.check("renewal never changes any active-loan counter", users.equals(s.rowsOf(observer, "user")), s.rowsOf(observer, "user").toString());
    }

    private static void invariant(BusinessSliceHarness s, MysqlObserver observer, String what) throws SQLException {
        var state = snapshot(s, observer);
        boolean correct = state.users().stream().map(LibraryBorrowingChecks::parts).allMatch(user -> {
            long active = state.loans().stream().map(LibraryBorrowingChecks::parts).filter(loan -> loan[LOAN_READER].equals(user[0]) && active(loan)).count();
            int counter = Integer.parseInt(user[USER_COUNTER]);
            return counter == active && counter >= 0 && counter <= LIMIT;
        });
        s.check(what, correct, "users=" + state.users() + ", loans=" + state.loans());
    }
    private static int counter(BusinessSliceHarness s, MysqlObserver observer, long id) throws SQLException {
        return Integer.parseInt(parts(row(s.rowsOf(observer, "user"), id))[USER_COUNTER]);
    }
    private static String row(List<String> rows, long id) { return rows.stream().filter(r -> r.startsWith(id + "|")).findFirst().orElseThrow(); }
    private static String[] parts(String row) { return row.split("\\|", -1); }
    private static boolean active(String[] loan) { return loan[LOAN_RETURNED].equals("0"); }
    private record Snapshot(List<String> users, List<String> loans, List<String> copies) {
        Snapshot { users = List.copyOf(users); loans = List.copyOf(loans); copies = List.copyOf(copies); }
    }
    private static Snapshot snapshot(BusinessSliceHarness s, MysqlObserver observer) throws SQLException {
        return new Snapshot(s.rowsOf(observer, "user"), s.rowsOf(observer, "loan"), s.rowsOf(observer, "copy"));
    }
    private static void unchanged(BusinessSliceHarness s, MysqlObserver observer, Snapshot before, String what) throws SQLException {
        var after = snapshot(s, observer); s.check(what, before.equals(after), after.toString());
    }
    private static Map<String, String> bearer(int id) { return Map.of("Authorization", "Bearer 00000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", id)); }
    private static HttpAssertionClient.Response borrow(BusinessSliceHarness s, HttpAssertionClient http, int reader, long copy) throws Exception {
        return http.postJson(s.baseUrl() + "/api/loans", bearer(reader), "{\"barcode\":\"C" + copy + "\"}");
    }
    private static HttpAssertionClient.Response giveBack(BusinessSliceHarness s, HttpAssertionClient http, int reader, long loan) throws Exception {
        return http.postJson(s.baseUrl() + "/api/loans/" + loan + "/return", bearer(reader), "{}");
    }
    private static List<HttpAssertionClient.Response> parallel(List<Callable<HttpAssertionClient.Response>> tasks) throws Exception {
        var barrier = new CyclicBarrier(tasks.size());
        var gated = tasks.stream().<Callable<HttpAssertionClient.Response>>map(task -> () -> {
            barrier.await(5, TimeUnit.SECONDS); return task.call();
        }).toList();
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var results = new ArrayList<HttpAssertionClient.Response>();
            for (var future : pool.invokeAll(gated, 25, TimeUnit.SECONDS)) results.add(future.get());
            return List.copyOf(results);
        }
    }
    private static String statuses(List<HttpAssertionClient.Response> responses) { return responses.stream().map(HttpAssertionClient.Response::statusCode).toList().toString(); }
    private static void status(BusinessSliceHarness s, String what, HttpAssertionClient.Response response, int expected) {
        s.record(what, response); s.expectStatus(what, response, expected);
    }
    private static void error(BusinessSliceHarness s, String what, HttpAssertionClient.Response response, Failure expected) {
        status(s, what, response, expected.status()); s.expectField(what + " error code", response, "code", expected.code());
    }
}
