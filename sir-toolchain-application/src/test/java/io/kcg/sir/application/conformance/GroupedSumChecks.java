package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Same Money IT application: group cardinality, exact carriers and an observable after-count barrier. */
final class GroupedSumChecks {
    static final String RESOURCE="/conformance/mysql/GroupedSumFixture.java.txt";
    static final String ROUTE="/api/measurements/groups";
    static final String BARRIER="/api/fixture/group-barrier";
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private GroupedSumChecks() {}

    static void install(BusinessSliceHarness s) throws Exception {
        DecimalSumChecks.installFixture(s,RESOURCE,"GroupedSumFixture.java");
    }

    static void verify(BusinessSliceHarness s,MysqlObserver observer,HttpAssertionClient http) throws Exception {
        var before=s.rowsOf(observer,"summary_entry");
        var categories=s.rowsOf(observer,"summary_category");
        try(var trace=s.openRuntimeTrace()) {
            trace.start();
            page(s,http,range(1)+"&page=1&size=2",3,List.of(101L,102L),List.of("3.00","0"));
            var observed=trace.stop();
            s.check("group page executes exactly count plus current page",observed.selectCount()==2,observed.toString());
        }
        page(s,http,range(1)+"&page=2&size=2",3,List.of(103L),List.of("199999999999999999.98"));
        page(s,http,range(1)+"&page=20&size=2",3,List.of(),List.of());
        var defaults=page(s,http,range(1),3,List.of(101L,102L,103L),List.of("3.00","0","199999999999999999.98"));
        if(defaults!=null) s.check("group default page policy is the existing 1/20",defaults.path("page").asInt()==1&&defaults.path("size").asInt()==20,defaults.toString());
        page(s,http,range(3),0,List.of(),List.of());
        page(s,http,range(1)+"&label="+URLEncoder.encode("' OR 1=1 --",StandardCharsets.UTF_8),0,List.of(),List.of());
        invalid(s,http,range(1)+"&page=0","BadGroupPage");
        invalid(s,http,ROUTE+"?owner=1&until=2026-02-01T00:00:00Z","BadGroupRange");
        invalid(s,http,ROUTE+"?owner=1&since=2026-02-01T00:00:00Z&until=2026-01-01T00:00:00Z","BadGroupRange");
        s.check("group reads leave all entries and categories unchanged",before.equals(s.rowsOf(observer,"summary_entry"))
            &&categories.equals(s.rowsOf(observer,"summary_category")),"snapshot before controlled concurrency fixture");
        snapshot(s,http);
        warnings(s,http,"strict session");
    }

    static void warnings(BusinessSliceHarness s,HttpAssertionClient http,String mode) throws Exception {
        for(String stage:List.of("count","data")) {
            var response=http.get(s.baseUrl()+"/api/fixture/group-warning?stage="+stage,Map.of());
            s.record("G8 group warning at "+stage+" ("+mode+")",response);
            s.expectStatus("group rejects current Statement warning at "+stage+" ("+mode+")",response,500);
            s.check("group warning failure does not expose SQL",!response.body().contains("CAST(")&&!response.body().contains("summary_entry"),response.body());
        }
        page(s,http,range(1)+"&page=1&size=2",3,List.of(101L,102L),List.of("3.00","0"));
    }

    private static void snapshot(BusinessSliceHarness s,HttpAssertionClient http) throws Exception {
        var armed=http.postJson(s.baseUrl()+BARRIER+"/arm",Map.of(),"{}");
        if(!s.expectStatus("after-count barrier can be armed",armed,200))return;
        try(var worker=Executors.newSingleThreadExecutor()) {
            var pending=worker.submit(() -> http.get(s.baseUrl()+range(99),Map.of()));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean ready=false;
                while(System.nanoTime()<deadline&&!pending.isDone()) {
                    var status=http.get(s.baseUrl()+BARRIER+"/status",Map.of());
                    if(status.statusCode()==200&&status.body().trim().equals("ready")){ready=true;break;}
                    Thread.sleep(40);
                }
                s.check("real group count finished before the insert barrier",ready,"fixed fixture intercepts after actual COUNT DISTINCT; no SQL mocking");
                if(ready) s.fixtureStatement("INSERT INTO summary_entry(id,owner,category_id,amount,occurred_at,label,removed) VALUES (9002,99,902,2.00,'2026-01-10 00:00:00','snapshot',0)");
            } finally { http.postJson(s.baseUrl()+BARRIER+"/release",Map.of(),"{}"); }
            var response=pending.get(8,TimeUnit.SECONDS);
            checkPage(s,response,1,List.of(901L),List.of("1.00"),"same page remains on the count snapshot after another transaction commits");
            page(s,http,range(99),2,List.of(901L,902L),List.of("1.00","2.00"));
        } finally { s.fixtureStatement("DELETE FROM summary_entry WHERE id=9002"); }
    }

    private static String range(long owner) {
        return ROUTE+"?owner="+owner+"&since=2026-01-01T00:00:00Z&until=2026-02-01T00:00:00Z";
    }
    private static JsonNode page(BusinessSliceHarness s,HttpAssertionClient http,String path,long total,List<Long> keys,List<String> amounts) throws Exception {
        var response=http.get(s.baseUrl()+path,Map.of());
        s.record("G8 grouped "+path,response);
        return checkPage(s,response,total,keys,amounts,"grouped page "+path);
    }
    static JsonNode checkPage(BusinessSliceHarness s,HttpAssertionClient.Response response,long total,List<Long> keys,List<String> amounts,String label) throws Exception {
        if(!s.expectStatus(label,response,200))return null;
        var body=JSON.readTree(response.body());
        var records=body.path("records");
        s.check(label+" total is group cardinality",body.path("total").isIntegralNumber()&&body.path("total").longValue()==total,body.toString());
        s.check(label+" records are bounded and ordered",records.isArray()&&records.size()==keys.size(),body.toString());
        if(records.isArray()&&records.size()==keys.size()) for(int i=0;i<keys.size();i++) {
            var row=records.get(i);
            s.check(label+" exact key/amount at "+i,row.size()==2&&row.path("key").isIntegralNumber()
                &&row.path("key").longValue()==keys.get(i)&&row.path("total").isNumber()
                &&row.path("total").decimalValue().compareTo(new BigDecimal(amounts.get(i)))==0,row.toString());
        }
        return body;
    }
    private static void invalid(BusinessSliceHarness s,HttpAssertionClient http,String path,String code) throws Exception {
        var response=http.get(s.baseUrl()+path,Map.of());s.record("G8 invalid group input",response);
        if(s.expectStatus("invalid group input is rejected",response,400))
            s.check("declared group error name is preserved",JSON.readTree(response.body()).path("code").asText().equals(code),response.body());
    }
}
