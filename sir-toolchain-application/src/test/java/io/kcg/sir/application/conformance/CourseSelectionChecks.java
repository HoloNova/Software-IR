package io.kcg.sir.application.conformance;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Independent HTTP/observer assertions against the actual topic; fixture SQL is confined to the owned schema. */
final class CourseSelectionChecks {
    private CourseSelectionChecks() {}
    static final String ENROLL_ROUTE = "/courses/{id}/enroll";
    enum Failure {
        NOT_FOUND(404, "CourseNotFound"), NOT_OPEN(409, "CourseNotOpen"), DUPLICATE(409, "AlreadyEnrolled"),
        CLASH(409, "TimeConflict"), MISSING(409, "MissingPrerequisites"), FORBIDDEN(403, "Forbidden"),
        FROZEN(409, "CoursePublished"), INVALID_PRIOR(400, "InvalidPrerequisite"),
        PRIOR_EXISTS(409, "PrerequisiteExists"), COMPLETION_EXISTS(409, "CompletionExists"),
        GONE(404, "EnrollmentNotFound"), COUNTER(409, "EnrollmentCounterMismatch"), FULL(409, "CourseFull");
        private final int status;
        private final String code;
        Failure(int status, String code) { this.status = status; this.code = code; }
        int status() { return status; }
        String code() { return code; }
    }
    static void verify(BusinessSliceHarness s, MysqlObserver observer, HttpAssertionClient http) throws Exception {
        invariant(s, observer, "seed seat counters match actual enrollments");
        prerequisitesAndIntervals(s, observer, http);
        configuration(s, observer, http);
        numericInputs(s, observer, http);
        concurrency(s, observer, http);
        waitAndRecheck(s, observer, http);
        lateRollback(s, observer, http);
        invariant(s, observer, "all final counters match enrollments and capacity");
    }
    private static void prerequisitesAndIntervals(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        var before = snapshot(s, o);
        status(s, "anonymous selection is refused", h.postJson(s.baseUrl() + "/api" + ENROLL_ROUTE.replace("{id}", "10"), Map.of(), "{}"), 401);
        error(s, "draft course is not open", enroll(s, h, 10, 14), Failure.NOT_OPEN);
        error(s, "unknown course is not found", enroll(s, h, 10, 999), Failure.NOT_FOUND);
        for (int actor : List.of(10, 11, 13, 25))
            error(s, "none, partial and same-count wrong prerequisite sets are refused for " + actor, enroll(s, h, actor, 10), Failure.MISSING);
        unchanged(s, o, before, "prerequisite refusals and authentication never reserve seats");
        var selected = enroll(s, h, 12, 10);
        status(s, "all required courses allow selection", selected, 201);
        long enrollment = selected.statusCode() == 201 ? Long.parseLong(BusinessSliceHarness.field(selected.body(), "id")) : -1;
        before = snapshot(s, o);
        error(s, "repeat selection has its specific declared failure", enroll(s, h, 12, 10), Failure.DUPLICATE);
        error(s, "overlapping weekly periods are refused", enroll(s, h, 12, 12), Failure.CLASH);
        unchanged(s, o, before, "duplicate and time-conflict checks do not write");
        status(s, "adjacent half-open periods are allowed", enroll(s, h, 12, 11), 201);
        error(s, "two passed courses do not satisfy a four-course requirement", enroll(s, h, 12, 13), Failure.MISSING);
        status(s, "all four direct prerequisites work without an artificial one-course limit", enroll(s, h, 24, 13), 201);
        before = snapshot(s, o);
        if (enrollment > 0) {
            error(s, "another student cannot drop this enrollment", drop(s, h, 11, enrollment), Failure.FORBIDDEN);
            unchanged(s, o, before, "foreign drop does not change any seat");
            status(s, "owner can drop a course", drop(s, h, 12, enrollment), 200);
            before = snapshot(s, o);
            error(s, "duplicate drop is not a second seat release", drop(s, h, 12, enrollment), Failure.GONE);
            unchanged(s, o, before, "duplicate drop preserves the course counter");
            status(s, "a dropped course can be selected again", enroll(s, h, 12, 10), 201);
        }
    }
    private static void configuration(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        var before = snapshot(s, o);
        error(s, "students cannot add their own prerequisite exemptions", post(s,h,10,"/courses/15/prerequisites","{\"requiredId\":1}"), Failure.FORBIDDEN);
        error(s, "students cannot publish courses", post(s,h,10,"/courses/15/publish","{}"), Failure.FORBIDDEN);
        error(s, "students cannot assert a passed transcript", post(s,h,10,"/completions","{\"studentId\":10,\"courseId\":2}"), Failure.FORBIDDEN);
        unchanged(s, o, before, "configuration authorization is checked before writing");
        status(s, "administrator records the missing passed course", post(s,h,1,"/completions","{\"studentId\":11,\"courseId\":2}"), 201);
        before = snapshot(s, o);
        error(s, "completion facts are unique", post(s,h,1,"/completions","{\"studentId\":11,\"courseId\":2}"), Failure.COMPLETION_EXISTS);
        unchanged(s, o, before, "duplicate completion does not alter transcript or seat counts");
        status(s, "selection sees newly committed completion", enroll(s,h,11,10), 201);
        status(s, "draft prerequisite is configurable", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":1}"), 201);
        error(s, "duplicate prerequisite is a declared conflict", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":1}"), Failure.PRIOR_EXISTS);
        error(s, "self prerequisite is rejected", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":15}"), Failure.INVALID_PRIOR);
        error(s, "unpublished prerequisite cannot create a cycle", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":14}"), Failure.INVALID_PRIOR);
        long link = link(s, o, 15);
        error(s, "students cannot remove a prerequisite", delete(s,h,10,"/prerequisites/"+link), Failure.FORBIDDEN);
        status(s, "administrator may remove a draft prerequisite", delete(s,h,1,"/prerequisites/"+link), 200);
        status(s, "removed draft prerequisite may be added again", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":1}"), 201);
        status(s, "draft can require a second course", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":2}"), 201);
        status(s, "publishing fixes the set of required courses", post(s,h,1,"/courses/15/publish","{}"), 200);
        before = snapshot(s, o);
        error(s, "published prerequisites cannot be added", post(s,h,1,"/courses/15/prerequisites","{\"requiredId\":3}"), Failure.FROZEN);
        error(s, "published prerequisites cannot be removed", delete(s,h,1,"/prerequisites/"+link(s,o,15)), Failure.FROZEN);
        error(s, "published state cannot be replayed", post(s,h,1,"/courses/15/publish","{}"), Failure.FROZEN);
        unchanged(s,o,before,"a published curriculum cannot change beneath an enrollment check");
        status(s, "configured published course checks the complete transcript", enroll(s,h,11,15), 201);
    }
    private static void numericInputs(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        var before = snapshot(s, o);
        String valid = "{\"code\":\"NEW\",\"name\":\"New Course\",\"teacherId\":2,\"capacity\":6,\"weekday\":7,\"startPeriod\":12,\"endPeriod\":13}";
        for (String field : List.of("weekday", "startPeriod", "endPeriod", "capacity")) {
            String regex = "\\\"" + field + "\\\":\\d+";
            for (String body : List.of(valid.replaceAll(regex,"\""+field+"\":null"), valid.replaceAll(","+regex,""))) {
                var response = post(s,h,1,"/courses",body);
                status(s, "missing or null " + field + " is refused before SQL", response,400);
                s.expectField("numeric null is a structured input failure",response,"code","INVALID_REQUEST");
                s.check("the parsed payload identifies the actual missing number " + field,
                        response.body().contains("\"path\":\""+field+"\"") && response.body().contains("\"code\":\"NotNull\""),response.body());
            }
        }
        for (String invalid : List.of(valid.replace("\"weekday\":7", "\"weekday\":8"),
                valid.replace("\"startPeriod\":12", "\"startPeriod\":0"), valid.replace("\"endPeriod\":13", "\"endPeriod\":12"),
                valid.replace("\"endPeriod\":13", "\"endPeriod\":11"), valid.replace("\"capacity\":6", "\"capacity\":0")))
            status(s,"out-of-range, empty or reverse schedule is rejected",post(s,h,1,"/courses",invalid),400);
        unchanged(s,o,before,"invalid schedules create no course and change no business table");
        var created = post(s,h,1,"/courses",valid);
        status(s,"last valid weekly period creates a draft",created,201);
        if (created.statusCode()==201) s.expectField("new course is not implicitly published",created,"published","false");
    }
    private static void concurrency(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        var results = parallel(List.of(() -> enroll(s,h,14,21), () -> enroll(s,h,14,22)));
        s.check("one student cannot concurrently enter two overlapping courses", count(results,201)==1 && count(results,409)==1,statuses(results));
        for (var r : results) if(r.statusCode()!=201) error(s,"losing concurrent selection sees a time conflict",r,Failure.CLASH);
        results = parallel(List.of(() -> enroll(s,h,15,23), () -> enroll(s,h,15,24)));
        s.check("same periods on different weekdays remain independent",count(results,201)==2,statuses(results));
        var seats = new ArrayList<Callable<HttpAssertionClient.Response>>();
        for(int actor=30;actor<=37;actor++) { int student=actor; seats.add(() -> enroll(s,h,student,30)); }
        results=parallel(seats);
        s.check("eight students claim exactly three seats",count(results,201)==3 && count(results,409)==5,statuses(results));
        for(var r:results) if(r.statusCode()!=201) error(s,"full course keeps its declared error",r,Failure.FULL);
        var held = s.rowsOf(o,"enrollment").stream().filter(r -> parts(r)[2].equals("30")).toList();
        s.check("stored roster contains exactly three seats",held.size()==3,held.toString());
        if(!held.isEmpty()) {
            String[] enrollment=parts(held.getFirst()); long id=Long.parseLong(enrollment[0]); int student=Integer.parseInt(enrollment[1]);
            var returns=new ArrayList<Callable<HttpAssertionClient.Response>>();
            for(int i=0;i<8;i++) returns.add(() -> drop(s,h,student,id));
            results=parallel(returns);
            s.check("eight drops release only one seat",count(results,200)==1 && count(results,404)==7,statuses(results));
            for(var r:results) if(r.statusCode()!=200) error(s,"replayed drop follows not-found",r,Failure.GONE);
        }
        invariant(s,o,"all concurrent choices and drops preserve seat accounting");
    }
    private static void waitAndRecheck(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()) {
            s.fixtureStatement("START TRANSACTION");
            try {
                s.fixtureStatement("SELECT id FROM user WHERE id=16 FOR UPDATE");
                var request=pool.submit(() -> enroll(s,h,16,21));
                boolean waiting=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline) {
                    if(s.fixtureScalar("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l "
                            + "ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE "
                            + "WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='user'")>0) { waiting=true; break; }
                    Thread.sleep(50);
                }
                s.check("enrollment demonstrably waits on the common student lock",waiting,"performance_schema.data_lock_waits");
                // TEST_FIXTURE_SQL: commit a competing enrollment only after the real HTTP lock wait is visible.
                s.fixtureStatement("INSERT INTO enrollment(student_id,course_id,created_at) VALUES(16,22,UTC_TIMESTAMP(6))");
                s.fixtureStatement("UPDATE course SET enrolled=enrolled+1 WHERE id=22");
                s.fixtureStatement("COMMIT");
                error(s,"after waiting, the request sees the committed conflicting course",request.get(30,TimeUnit.SECONDS),Failure.CLASH);
                s.check("waiting student has only the fixture's committed enrollment",s.rowsOf(o,"enrollment").stream()
                        .map(CourseSelectionChecks::parts).filter(r->r[1].equals("16")).count()==1,s.rowsOf(o,"enrollment").toString());
            } finally { s.fixtureStatement("ROLLBACK"); }
        }
    }
    private static void lateRollback(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        var before=snapshot(s,o);
        // TEST_FIXTURE_SQL: a late unique conflict proves rollback AFTER the normal course seat update.
        s.fixtureStatement("CREATE TRIGGER fixture_course_late_duplicate BEFORE INSERT ON enrollment FOR EACH ROW "
                + "SET NEW.student_id=IF(NEW.course_id=40,25,NEW.student_id)");
        try { error(s,"late insert collision rolls back the seat increment",enroll(s,h,22,40),Failure.DUPLICATE);
            unchanged(s,o,before,"seat and enrollment rows roll back together after the late insert error");
        } finally { s.fixtureStatement("DROP TRIGGER fixture_course_late_duplicate"); }
        s.fixtureStatement("CREATE TABLE fixture_enrollment_hold (id BIGINT PRIMARY KEY, enrollment_id BIGINT NOT NULL, "
                + "FOREIGN KEY(enrollment_id) REFERENCES enrollment(id))");
        try {
            s.fixtureStatement("INSERT INTO fixture_enrollment_hold VALUES(1,400)");
            status(s,"late fixture FK delete failure is not a successful drop",drop(s,h,25,400),500);
            unchanged(s,o,before,"seat decrement rolls back if the following enrollment delete fails");
        } finally { s.fixtureStatement("DROP TABLE fixture_enrollment_hold"); }
        s.fixtureStatement("UPDATE course SET enrolled=0 WHERE id=40");
        try {
            var corrupted=snapshot(s,o);
            error(s,"invalid old counter cannot be decremented below zero",drop(s,h,25,400),Failure.COUNTER);
            unchanged(s,o,corrupted,"inconsistent counter is refused rather than silently deleting a roster row");
        } finally { s.fixtureStatement("UPDATE course SET enrolled=1 WHERE id=40"); }
    }
    private record Snapshot(List<String> courses,List<String> enrollments,List<String> prerequisites,List<String> completions) {
        Snapshot { courses=List.copyOf(courses); enrollments=List.copyOf(enrollments); prerequisites=List.copyOf(prerequisites); completions=List.copyOf(completions); }
    }
    private static Snapshot snapshot(BusinessSliceHarness s,MysqlObserver o) throws SQLException {
        return new Snapshot(s.rowsOf(o,"course"),s.rowsOf(o,"enrollment"),s.rowsOf(o,"prerequisite"),s.rowsOf(o,"completed_course"));
    }
    private static void unchanged(BusinessSliceHarness s,MysqlObserver o,Snapshot before,String message) throws SQLException {
        var after=snapshot(s,o); s.check(message,before.equals(after),after.toString());
    }
    private static void invariant(BusinessSliceHarness s,MysqlObserver o,String message) throws SQLException {
        var snapshot=snapshot(s,o);
        boolean good=snapshot.courses().stream().map(CourseSelectionChecks::parts).allMatch(course -> {
            int enrolled=Integer.parseInt(course[5]),capacity=Integer.parseInt(course[4]);
            long actual=snapshot.enrollments().stream().map(CourseSelectionChecks::parts).filter(e->e[2].equals(course[0])).count();
            return enrolled==actual && enrolled>=0 && enrolled<=capacity;
        });
        s.check(message,good,snapshot.toString());
    }
    private static long link(BusinessSliceHarness s,MysqlObserver o,long course) throws SQLException {
        return s.rowsOf(o,"prerequisite").stream().map(CourseSelectionChecks::parts).filter(r->r[1].equals(Long.toString(course)))
                .mapToLong(r->Long.parseLong(r[0])).findFirst().orElseThrow();
    }
    private static List<HttpAssertionClient.Response> parallel(List<Callable<HttpAssertionClient.Response>> tasks) throws Exception {
        var barrier=new CyclicBarrier(tasks.size());
        var gated=tasks.stream().<Callable<HttpAssertionClient.Response>>map(task->()-> { barrier.await(5,TimeUnit.SECONDS); return task.call(); }).toList();
        try(var pool=Executors.newFixedThreadPool(tasks.size())) {
            var result=new ArrayList<HttpAssertionClient.Response>();
            for(var future:pool.invokeAll(gated,25,TimeUnit.SECONDS)) result.add(future.get());
            return List.copyOf(result);
        }
    }
    private static Map<String,String> bearer(int actor) { return Map.of("Authorization","Bearer 00000000-0000-4000-8000-"+String.format(java.util.Locale.ROOT,"%012d",actor)); }
    private static HttpAssertionClient.Response post(BusinessSliceHarness s,HttpAssertionClient h,int actor,String path,String json) throws Exception { return h.postJson(s.baseUrl()+"/api"+path,bearer(actor),json); }
    private static HttpAssertionClient.Response delete(BusinessSliceHarness s,HttpAssertionClient h,int actor,String path) throws Exception { return h.delete(s.baseUrl()+"/api"+path,bearer(actor)); }
    private static HttpAssertionClient.Response enroll(BusinessSliceHarness s,HttpAssertionClient h,int actor,long course) throws Exception { return post(s,h,actor,ENROLL_ROUTE.replace("{id}",Long.toString(course)),"{}"); }
    private static HttpAssertionClient.Response drop(BusinessSliceHarness s,HttpAssertionClient h,int actor,long id) throws Exception { return delete(s,h,actor,"/enrollments/"+id); }
    private static long count(List<HttpAssertionClient.Response> responses,int status) { return responses.stream().filter(r->r.statusCode()==status).count(); }
    private static String statuses(List<HttpAssertionClient.Response> responses) { return responses.stream().map(HttpAssertionClient.Response::statusCode).toList().toString(); }
    private static String[] parts(String row) { return row.split("\\|",-1); }
    private static void status(BusinessSliceHarness s,String why,HttpAssertionClient.Response response,int expected) { s.record(why,response); s.expectStatus(why,response,expected); }
    private static void error(BusinessSliceHarness s,String why,HttpAssertionClient.Response response,Failure expected) { status(s,why,response,expected.status()); s.expectField(why+" error code",response,"code",expected.code()); }
}
