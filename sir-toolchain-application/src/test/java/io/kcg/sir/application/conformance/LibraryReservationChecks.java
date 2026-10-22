package io.kcg.sir.application.conformance;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Exercises the same actual library instance after its existing borrowing checks. */
final class LibraryReservationChecks {
    private LibraryReservationChecks() {}
    static final String RESERVE_ROUTE="/reservations", CLAIM_ROUTE="/reservations/{id}/claim", CANCEL_ROUTE="/reservations/{id}";
    enum Failure {
        FORBIDDEN(403,"Forbidden"), DUPLICATE(409,"AlreadyReserved"), HOLDING(409,"AlreadyBorrowingBook"),
        NOT_RESERVABLE(409,"NotReservable"), NOT_HEAD(409,"NotQueueHead"), PRIORITY(409,"QueueHasPriority"),
        WRONG_BOOK(409,"WrongBook"), FULL(409,"BorrowLimitExceeded"), UNAVAILABLE(409,"CopyNotAvailable"),
        MISSING(404,"ReservationNotFound");
        private final int status; private final String code;
        Failure(int status,String code) { this.status=status; this.code=code; }
        int status() { return status; } String code() { return code; }
    }
    static void verify(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        invariant(s,o,"queue fixture starts with accurate loan counters and copy ownership");
        fifoAndReturn(s,o,h);
        fullHeadAndStaffCancellation(s,o,h);
        multipleCopiesAndReplay(s,o,h);
        parallelAdmission(s,o,h);
        cancellationRace(s,o,h);
        waitAndRecheck(s,o,h);
        lateFailures(s,o,h);
        invariant(s,o,"final loans, copy ownership and user counters agree after every queue transition");
    }
    private static void fifoAndReturn(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        var before=snapshot(s,o);
        status(s,"anonymous reservation is refused",h.postJson(s.baseUrl()+"/api"+RESERVE_ROUTE,Map.of(),"{\"bookId\":10}"),401);
        status(s,"anonymous private queue read is refused",h.get(s.baseUrl()+"/api"+RESERVE_ROUTE,Map.of()),401);
        error(s,"available books are borrowed directly rather than queued",reserve(s,h,10,1),Failure.NOT_RESERVABLE);
        error(s,"lost copies do not count as borrowed books",reserve(s,h,10,12),Failure.NOT_RESERVABLE);
        error(s,"a book without copies does not create an impossible waiting list",reserve(s,h,10,13),Failure.NOT_RESERVABLE);
        error(s,"a current borrower cannot reserve the same book",reserve(s,h,14,10),Failure.HOLDING);
        unchanged(s,o,before,"admission and authentication failures write nothing");
        long first=ticket(s,reserve(s,h,10,10),"first reader reserves the borrowed title");
        if(first<0) return;
        before=snapshot(s,o);
        error(s,"one pending ticket per reader and title",reserve(s,h,10,10),Failure.DUPLICATE);
        unchanged(s,o,before,"duplicate queue admission cannot create another position");
        long second=ticket(s,reserve(s,h,11,10),"second reader joins behind the first");
        if(second<0) return;
        s.check("ticket ids give a deterministic same-title order",first<second,first+" < "+second);
        var mine=h.get(s.baseUrl()+"/api"+RESERVE_ROUTE+"?page=1&size=10",bearer(11));
        if(status(s,"reader can list their own tickets",mine,200)) {
            s.expectField("private list has one record",mine,"total","1");
            s.check("private list contains only this readers pending ticket",mine.body().contains("\"id\":"+second+",")
                    && !mine.body().contains("\"id\":"+first+","),mine.body());
        }
        before=snapshot(s,o);
        error(s,"normal barcode borrowing cannot bypass a pending queue",borrow(s,h,13,"C100"),Failure.PRIORITY);
        error(s,"non-head cannot claim even when they know a valid barcode",claim(s,h,11,second,"C100"),Failure.NOT_HEAD);
        error(s,"foreign ticket cannot be claimed",claim(s,h,13,first,"C100"),Failure.FORBIDDEN);
        error(s,"foreign ticket cannot be cancelled by a reader",cancel(s,h,13,first),Failure.FORBIDDEN);
        error(s,"head cannot claim a still borrowed copy",claim(s,h,10,first,"C100"),Failure.UNAVAILABLE);
        status(s,"client cannot inject a reservation owner",post(s,h,13,RESERVE_ROUTE,"{\"bookId\":10,\"readerId\":10}"),400);
        unchanged(s,o,before,"priority, ownership and late unavailable-copy failures preserve all four ledgers");
        status(s,"original borrower can return even with a nonempty queue",giveBack(s,h,14,100),200);
        s.check("return releases the physical copy but never automatically assigns it",counter(s,o,10)==0 && counter(s,o,11)==0
                && ticketPresent(s,o,first) && ticketPresent(s,o,second) && copyStatus(s,o,100).equals("AVAILABLE"),snapshot(s,o).toString());
        before=snapshot(s,o);
        error(s,"a returned available copy still belongs to the queue",borrow(s,h,13,"C100"),Failure.PRIORITY);
        error(s,"wrong title is rejected without losing the head",claim(s,h,10,first,"C120"),Failure.WRONG_BOOK);
        unchanged(s,o,before,"rejected returned-copy requests do not lose priority or slots");
        var loan=claim(s,h,10,first,"C100");
        if(!status(s,"head explicitly claims the returned copy",loan,201)) return;
        long id=Long.parseLong(BusinessSliceHarness.field(loan.body(),"id"));
        s.check("successful claim atomically consumes only the first ticket",!ticketPresent(s,o,first) && ticketPresent(s,o,second)
                && counter(s,o,10)==1 && copyStatus(s,o,100).equals("BORROWED"),snapshot(s,o).toString());
        before=snapshot(s,o);
        error(s,"consumed ticket cannot be replayed",claim(s,h,10,first,"C100"),Failure.MISSING);
        unchanged(s,o,before,"replay cannot allocate a second loan");
        status(s,"claimed loan follows the ordinary return protocol",giveBack(s,h,10,id),200);
        status(s,"the next reader is promoted by the committed ticket removal",claim(s,h,11,second,"C100"),201);
    }
    private static void fullHeadAndStaffCancellation(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        long first=ticket(s,reserve(s,h,20,14),"a full reader may join without consuming a sixth slot");
        long second=ticket(s,reserve(s,h,12,14),"another reader follows the full head");
        if(first<0 || second<0) return;
        status(s,"return is not held hostage by a full queue head",giveBack(s,h,22,140),200);
        var before=snapshot(s,o);
        error(s,"full head retains the position rather than silently being skipped",claim(s,h,20,first,"C140"),Failure.FULL);
        error(s,"a follower cannot skip the full head",claim(s,h,12,second,"C140"),Failure.NOT_HEAD);
        unchanged(s,o,before,"full-head refusals preserve tickets and the newly available copy");
        status(s,"librarian may explicitly cancel an abandoned head",cancel(s,h,15,first),200);
        s.check("staff cancellation does not decrement a borrowers active loans",counter(s,o,20)==5 && !ticketPresent(s,o,first),snapshot(s,o).toString());
        status(s,"follower can claim only after explicit head cancellation",claim(s,h,12,second,"C140"),201);
    }
    private static void multipleCopiesAndReplay(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        long a=ticket(s,reserve(s,h,16,11),"multi-copy queue first ticket");
        long b=ticket(s,reserve(s,h,17,11),"multi-copy queue second ticket");
        long c=ticket(s,reserve(s,h,18,11),"multi-copy queue third ticket");
        if(a<0 || b<0 || c<0) return;
        status(s,"owner can cancel their head ticket",cancel(s,h,16,a),200);
        long tail=ticket(s,reserve(s,h,16,11),"cancelled reader may rejoin only at the tail");
        if(tail<0) return;
        s.check("rejoining cannot recover the old place",tail>c,tail+" > "+c);
        error(s,"cancelled ticket is not reusable",claim(s,h,16,a,"C110"),Failure.MISSING);
        error(s,"rejoined tail cannot skip remaining readers",claim(s,h,16,tail,"C110"),Failure.NOT_HEAD);
        status(s,"first physical copy returns",giveBack(s,h,21,110),200);
        status(s,"second physical copy returns",giveBack(s,h,21,111),200);
        var tasks=new ArrayList<Callable<HttpAssertionClient.Response>>();
        for(int i=0;i<8;i++) tasks.add(()->claim(s,h,17,b,"C110"));
        var replies=parallel(tasks);
        s.check("eight simultaneous claims consume the known ticket exactly once",count(replies,201)==1 && count(replies,404)==7,statuses(replies));
        for(var reply:replies) if(reply.statusCode()!=201) error(s,"losing replays see the consumed ticket",reply,Failure.MISSING);
        status(s,"a second available copy serves the next ticket",claim(s,h,18,c,"C111"),201);
        status(s,"librarian adds a copy while the title still has a queue",post(s,h,15,"/copies","{\"bookId\":11,\"barcode\":\"C112\"}"),201);
        error(s,"a new physical copy does not allow ordinary borrowing to cut the queue",borrow(s,h,13,"C112"),Failure.PRIORITY);
        status(s,"added copy can be collected by the remaining head",claim(s,h,16,tail,"C112"),201);
        s.check("each multi-copy claimant has one active loan",counter(s,o,16)==1 && counter(s,o,17)==1 && counter(s,o,18)==1,snapshot(s,o).toString());
    }
    private static void parallelAdmission(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        var tasks=new ArrayList<Callable<HttpAssertionClient.Response>>();
        for(int i=0;i<8;i++) tasks.add(()->reserve(s,h,27,10));
        var results=parallel(tasks);
        s.check("concurrent duplicate enqueue has one success and seven declared conflicts",count(results,201)==1 && count(results,409)==7,statuses(results));
        for(var result:results) if(result.statusCode()!=201) error(s,"duplicate admission reports its exact declared error",result,Failure.DUPLICATE);
        results=parallel(List.of(()->borrow(s,h,19,"C150"),()->borrow(s,h,13,"C160")));
        s.check("queues on other titles do not prevent independent ordinary borrowing",count(results,201)==2,statuses(results));
        invariant(s,o,"all concurrent enqueues and claims preserve ledger equality");
    }
    private static void cancellationRace(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        var replies=parallel(List.of(()->cancel(s,h,28,820),()->claim(s,h,28,820,"C190")));
        boolean cancelled=replies.get(0).statusCode()==200 && replies.get(1).statusCode()==404;
        boolean claimed=replies.get(0).statusCode()==404 && replies.get(1).statusCode()==201;
        s.check("cancel and claim follow exactly one valid committed serial outcome",cancelled || claimed,statuses(replies));
        for(var reply:replies) if(reply.statusCode()==404) error(s,"losing transition sees the consumed pending ticket",reply,Failure.MISSING);
        s.check("cancel/claim race never loses a ticket with a partially allocated copy",!ticketPresent(s,o,820)
                && counter(s,o,28)==(claimed?1:0) && copyStatus(s,o,190).equals(claimed?"BORROWED":"AVAILABLE"),snapshot(s,o).toString());
    }
    private static void waitAndRecheck(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()) {
            s.fixtureStatement("START TRANSACTION");
            try {
                // TEST_FIXTURE_SQL: emulate an authorised cancellation, observing the actual Book lock wait.
                s.fixtureStatement("SELECT id FROM user WHERE id=25 FOR UPDATE");
                s.fixtureStatement("SELECT id FROM book WHERE id=17 FOR UPDATE");
                var request=pool.submit(()->claim(s,h,24,801,"C170"));
                boolean waiting=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline) {
                    if(s.fixtureScalar("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l "
                            +"ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE "
                            +"WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='book'")>0) { waiting=true; break; }
                    Thread.sleep(50);
                }
                s.check("claim demonstrably waits for the common title mutex",waiting,"performance_schema.data_lock_waits");
                s.fixtureStatement("DELETE FROM reservation WHERE id=800");
                s.fixtureStatement("COMMIT");
                status(s,"after waiting, follower rechecks the committed cancellation and becomes head",request.get(30,TimeUnit.SECONDS),201);
                s.check("both cancelled and consumed tickets are gone exactly once",!ticketPresent(s,o,800) && !ticketPresent(s,o,801)
                        && counter(s,o,24)==1 && counter(s,o,25)==0,snapshot(s,o).toString());
            } finally { s.fixtureStatement("ROLLBACK"); }
        }
    }
    private static void lateFailures(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        var before=snapshot(s,o);
        // TEST_FIXTURE_SQL: a child FK fails after slot, copy and loan writes, at ticket deletion.
        s.fixtureStatement("CREATE TABLE fixture_reservation_hold (id BIGINT PRIMARY KEY, reservation_id BIGINT NOT NULL, FOREIGN KEY(reservation_id) REFERENCES reservation(id))");
        try {
            s.fixtureStatement("INSERT INTO fixture_reservation_hold VALUES(1,810)");
            status(s,"late ticket deletion failure is not a successful claim",claim(s,h,26,810,"C180"),500);
            unchanged(s,o,before,"late delete rolls back slot, copy, loan and ticket together");
        } finally { s.fixtureStatement("DROP TABLE fixture_reservation_hold"); }
        // TEST_FIXTURE_SQL: independently force an insert failure BEFORE ticket deletion.
        s.fixtureStatement("CREATE TRIGGER fixture_reservation_loan_failure BEFORE INSERT ON loan FOR EACH ROW SET NEW.copy_id=IF(NEW.copy_id=180,999999,NEW.copy_id)");
        try {
            status(s,"late loan insertion failure keeps the pending ticket",claim(s,h,26,810,"C180"),500);
            unchanged(s,o,before,"late insert rolls back all preceding writes");
        } finally { s.fixtureStatement("DROP TRIGGER fixture_reservation_loan_failure"); }
        status(s,"the original ticket remains usable after both rolled-back attempts",claim(s,h,26,810,"C180"),201);
    }
    private record Snapshot(List<String> users,List<String> loans,List<String> copies,List<String> tickets) {
        Snapshot { users=List.copyOf(users); loans=List.copyOf(loans); copies=List.copyOf(copies); tickets=List.copyOf(tickets); }
    }
    private static Snapshot snapshot(BusinessSliceHarness s,MysqlObserver o) throws SQLException {
        return new Snapshot(s.rowsOf(o,"user"),s.rowsOf(o,"loan"),s.rowsOf(o,"copy"),s.rowsOf(o,"reservation"));
    }
    private static void unchanged(BusinessSliceHarness s,MysqlObserver o,Snapshot before,String why) throws SQLException {
        var after=snapshot(s,o); s.check(why,before.equals(after),after.toString());
    }
    private static void invariant(BusinessSliceHarness s,MysqlObserver o,String why) throws SQLException {
        var state=snapshot(s,o);
        boolean counters=state.users().stream().map(LibraryReservationChecks::parts).allMatch(u->{
            long active=state.loans().stream().map(LibraryReservationChecks::parts).filter(l->l[1].equals(u[0]) && l[6].equals("0")).count();
            return active==Integer.parseInt(u[2]) && active>=0 && active<=5;
        });
        boolean copies=state.copies().stream().map(LibraryReservationChecks::parts).allMatch(c->{
            long active=state.loans().stream().map(LibraryReservationChecks::parts).filter(l->l[2].equals(c[0]) && l[6].equals("0")).count();
            return c[3].equals("BORROWED")?active==1:active==0;
        });
        s.check(why,counters && copies,state.toString());
    }
    private static int counter(BusinessSliceHarness s,MysqlObserver o,long id) throws SQLException { return Integer.parseInt(parts(row(s.rowsOf(o,"user"),id))[2]); }
    private static String copyStatus(BusinessSliceHarness s,MysqlObserver o,long id) throws SQLException { return parts(row(s.rowsOf(o,"copy"),id))[3]; }
    private static boolean ticketPresent(BusinessSliceHarness s,MysqlObserver o,long id) throws SQLException { return s.rowsOf(o,"reservation").stream().anyMatch(r->r.startsWith(id+"|")); }
    private static String row(List<String> rows,long id) { return rows.stream().filter(r->r.startsWith(id+"|")).findFirst().orElseThrow(); }
    private static String[] parts(String row) { return row.split("\\|",-1); }
    private static long ticket(BusinessSliceHarness s,HttpAssertionClient.Response response,String why) {
        return status(s,why,response,201)?Long.parseLong(BusinessSliceHarness.field(response.body(),"id")):-1;
    }
    private static List<HttpAssertionClient.Response> parallel(List<Callable<HttpAssertionClient.Response>> tasks) throws Exception {
        var barrier=new CyclicBarrier(tasks.size());
        var gated=tasks.stream().<Callable<HttpAssertionClient.Response>>map(task->()->{barrier.await(5,TimeUnit.SECONDS);return task.call();}).toList();
        try(var pool=Executors.newFixedThreadPool(tasks.size())) {
            var results=new ArrayList<HttpAssertionClient.Response>();
            for(var result:pool.invokeAll(gated,25,TimeUnit.SECONDS)) results.add(result.get());
            return List.copyOf(results);
        }
    }
    private static Map<String,String> bearer(int reader) { return Map.of("Authorization","Bearer 00000000-0000-4000-8000-"+String.format(java.util.Locale.ROOT,"%012d",reader)); }
    private static HttpAssertionClient.Response post(BusinessSliceHarness s,HttpAssertionClient h,int reader,String path,String body) throws Exception { return h.postJson(s.baseUrl()+"/api"+path,bearer(reader),body); }
    private static HttpAssertionClient.Response reserve(BusinessSliceHarness s,HttpAssertionClient h,int reader,long book) throws Exception { return post(s,h,reader,RESERVE_ROUTE,"{\"bookId\":"+book+"}"); }
    private static HttpAssertionClient.Response claim(BusinessSliceHarness s,HttpAssertionClient h,int reader,long ticket,String barcode) throws Exception { return post(s,h,reader,CLAIM_ROUTE.replace("{id}",Long.toString(ticket)),"{\"barcode\":\""+barcode+"\"}"); }
    private static HttpAssertionClient.Response cancel(BusinessSliceHarness s,HttpAssertionClient h,int reader,long ticket) throws Exception { return h.delete(s.baseUrl()+"/api"+CANCEL_ROUTE.replace("{id}",Long.toString(ticket)),bearer(reader)); }
    private static HttpAssertionClient.Response borrow(BusinessSliceHarness s,HttpAssertionClient h,int reader,String barcode) throws Exception { return post(s,h,reader,"/loans","{\"barcode\":\""+barcode+"\"}"); }
    private static HttpAssertionClient.Response giveBack(BusinessSliceHarness s,HttpAssertionClient h,int reader,long loan) throws Exception { return post(s,h,reader,"/loans/"+loan+"/return","{}"); }
    private static long count(List<HttpAssertionClient.Response> replies,int code) { return replies.stream().filter(r->r.statusCode()==code).count(); }
    private static String statuses(List<HttpAssertionClient.Response> replies) { return replies.stream().map(HttpAssertionClient.Response::statusCode).toList().toString(); }
    private static boolean status(BusinessSliceHarness s,String why,HttpAssertionClient.Response reply,int expected) { s.record(why,reply); return s.expectStatus(why,reply,expected); }
    private static void error(BusinessSliceHarness s,String why,HttpAssertionClient.Response reply,Failure expected) { status(s,why,reply,expected.status()); s.expectField(why+" error code",reply,"code",expected.code()); }
}
