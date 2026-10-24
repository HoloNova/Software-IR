package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** Actual ledger declarations run in the existing Money IT, never a second application build. */
final class CalendarLedgerChecks {
    static final String RESOURCE="/conformance/mysql/CalendarSnapshotFixture.java.txt";
    static final String MONTH="/api/entries/monthly-totals?month=";
    private static final String BARRIER="/api/fixture/calendar";
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private CalendarLedgerChecks() {}
    static void install(BusinessSliceHarness s) throws Exception {DecimalSumChecks.installFixture(s,RESOURCE,"CalendarSnapshotFixture.java");}
    static void verify(BusinessSliceHarness s,HttpAssertionClient http,boolean nonUtc) throws Exception {
        var session=http.get(s.baseUrl()+BARRIER+"/session",Map.of());s.record("actual calendar JDBC and JVM session",session);
        if(s.expectStatus("inspect the actual generated datasource, not only environment settings",session,200)) {
            var settings=JSON.readTree(session.body());
            s.check("both calendar runtime modes use the observed JVM and actual session offsets",
                settings.path("jvmZone").asText().equals(nonUtc?"Pacific/Honolulu":"UTC")
                && settings.path("offsetSeconds").asText().equals(nonUtc?"19800":"0")
                && (!nonUtc || settings.path("timeZone").asText().equals("+05:30") && settings.path("sqlMode").asText().isEmpty()),session.body());
        }
        var anonymous=http.get(s.baseUrl()+MONTH+"2026-03",Map.of());s.expectStatus("calendar requires real authentication",anonymous,401);
        page(s,http,1,"2026-03","30.00");
        var forged=http.get(s.baseUrl()+MONTH+"2026-03&zone=UTC&userId=2",bearer(1));
        GroupedSumChecks.checkPage(s,forged,1,List.of(10L),List.of("30.00"),"request preferences cannot replace the saved actor preference");
        error(s,http.get(s.baseUrl()+MONTH+"2026-03",bearer(3)),400,"BadPeriod");
        for(String month:List.of("2026-1","2026-13","9999-12","0000-01"))error(s,http.get(s.baseUrl()+MONTH+month,bearer(1)),400,"BadPeriod");
        var missing=http.get(s.baseUrl()+"/api/entries/monthly-totals",bearer(1));error(s,missing,400,"BadPeriod");
        for(String zone:List.of("CST","+08:00","UTC+08:00","Mars/Base",""))
            error(s,setZone(s,http,1,zone),400,"INVALID_REQUEST");
        var nullZone=http.postJson(s.baseUrl()+"/api/me/time-zone",bearer(1),"{\"timeZone\":null}");error(s,nullZone,400,"INVALID_REQUEST");
        page(s,http,1,"2026-03","30.00");
        budget(s,http,10,false);
        try {s.fixtureStatement("UPDATE budget SET monthly_limit=29.99 WHERE id=10");budget(s,http,10,true);}
        finally {s.fixtureStatement("UPDATE budget SET monthly_limit=30.00 WHERE id=10");}
        for(long id:List.of(20L,30L))error(s,http.get(s.baseUrl()+"/api/budgets/"+id+"/exceeded?month=2026-03",bearer(1)),404,"BudgetNotFound");
        snapshot(s,http);
        utcAndHistoricJdbc(s,http);
        s.expectStatus("setting timezone cannot target another user",http.postJson(s.baseUrl()+"/api/me/time-zone",bearer(1),"{\"timeZone\":\"UTC\",\"id\":2}"),400);
        var other=http.get(s.baseUrl()+"/api/me/time-zone",bearer(2));s.expectStatus("other preference remains readable only as its actor",other,200);
        s.expectField("other preference is unchanged",other,"timeZone","UTC");
    }
    private static void snapshot(BusinessSliceHarness s,HttpAssertionClient http) throws Exception {
        s.expectStatus("arm actual after-user RR barrier",http.postJson(s.baseUrl()+BARRIER+"/arm",Map.of(),"{}"),200);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->http.get(s.baseUrl()+MONTH+"2026-03",bearer(1)));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);boolean ready=false;
                while(System.nanoTime()<deadline) {
                    if(http.get(s.baseUrl()+BARRIER+"/status",Map.of()).body().contains("ready")){ready=true;break;}
                    if(pending.isDone())break;
                    Thread.sleep(25);
                }
                s.check("calendar user was actually read in an RR transaction",ready,"pendingDone="+pending.isDone());
                if(ready)s.expectStatus("a later preference update commits separately",setZone(s,http,1,"UTC"),200);
            } finally {http.postJson(s.baseUrl()+BARRIER+"/release",Map.of(),"{}");}
            GroupedSumChecks.checkPage(s,pending.get(8,TimeUnit.SECONDS),1,List.of(10L),List.of("30.00"),"in-flight month keeps the originally read preference");
            page(s,http,1,"2026-03","109.00");
        } finally {s.expectStatus("restore saved region",setZone(s,http,1,"America/New_York"),200);}
    }
    private static void utcAndHistoricJdbc(BusinessSliceHarness s,HttpAssertionClient http) throws Exception {
        s.expectStatus("use a saved UTC zone for target minimum",setZone(s,http,1,"UTC"),200);
        try {
            page(s,http,1,"1000-01","1.00");
            var created=http.postJson(s.baseUrl()+"/api/entries",bearer(1),"{\"categoryId\":10,\"amount\":1.00,\"occurredAt\":\"1500-02-01T00:00:00Z\"}");
            s.record("calendar historical JDBC create",created);
            if(s.expectStatus("actual ledger create binds historical UTC without Timestamp drift",created,201)) {
                var body=JSON.readTree(created.body());var id=body.path("id").longValue();
                var read=http.get(s.baseUrl()+"/api/entries/"+id,bearer(1));
                s.record("calendar historical JDBC read",read);
                s.expectStatus("historical row is read through the generated Mapper",read,200);
                s.expectField("historical DATETIME fields round-trip as UTC",read,"occurredAt","1500-02-01T00:00:00Z");
                page(s,http,1,"1500-02","1.00");
                s.fixtureStatement("DELETE FROM entry WHERE id="+id);
            }
            var empty=http.get(s.baseUrl()+MONTH+"9999-11",bearer(1));
            GroupedSumChecks.checkPage(s,empty,0,List.of(),List.of(),"last complete target month remains bindable");
        } finally {s.expectStatus("restore New York after history test",setZone(s,http,1,"America/New_York"),200);}
    }
    private static void page(BusinessSliceHarness s,HttpAssertionClient http,int actor,String month,String total) throws Exception {
        var response=http.get(s.baseUrl()+MONTH+month,bearer(actor));s.record("calendar "+month,response);
        GroupedSumChecks.checkPage(s,response,1,List.of(10L),List.of(total),"user-zone month "+month);
    }
    private static void budget(BusinessSliceHarness s,HttpAssertionClient http,long id,boolean expected) throws Exception {
        var response=http.get(s.baseUrl()+"/api/budgets/"+id+"/exceeded?month=2026-03",bearer(1));s.record("calendar budget "+id,response);
        if(s.expectStatus("current specified budget rule is evaluated",response,200))
            s.check("equality is not exceeded; rule changes affect historical re-query",JSON.readTree(response.body()).isBoolean()&&JSON.readTree(response.body()).booleanValue()==expected,response.body());
    }
    private static void error(BusinessSliceHarness s,HttpAssertionClient.Response response,int status,String code) throws Exception {
        s.record("calendar refusal "+code,response);if(s.expectStatus("calendar refusal",response,status))s.expectField("declared error code",response,"code",code);
    }
    private static HttpAssertionClient.Response setZone(BusinessSliceHarness s,HttpAssertionClient http,int actor,String zone) throws Exception {
        return http.postJson(s.baseUrl()+"/api/me/time-zone",bearer(actor),JSON.writeValueAsString(Map.of("timeZone",zone)));
    }
    private static Map<String,String> bearer(int actor) {return Map.of("Authorization","Bearer 00000000-0000-4000-8000-"+String.format(Locale.ROOT,"%012d",actor));}
}
