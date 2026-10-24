package io.kcg.sir.application.conformance;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/** Real HTTP and independent MySQL observation on the actual renamed shop; never used as a local mock. */
final class ShopCancellationChecks {
    static final String CANCEL="/api/orders/{id}/cancel",PAY="/api/orders/{id}/pay";
    private ShopCancellationChecks() {}

    static void install(BusinessSliceHarness s) throws Exception {
        byte[] bytes=ConformanceFixtures.readResource("/conformance/mysql/ShopSessionFixture.java.txt").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file=s.projectRoot().resolve("src/main/java/example/multiplication/fixture/ShopSessionFixture.java");
        Files.createDirectories(file.getParent());Files.write(file,bytes);Files.createDirectories(s.evidenceRoot());
        Files.write(s.evidenceRoot().resolve("ShopSessionFixture.java.txt"),bytes);
    }
    static void verify(BusinessSliceHarness s,MysqlObserver observer,HttpAssertionClient http) throws Exception {
        successfulAndRepeated(s,observer,http);
        rejectionAndBounds(s,observer,http);
        concurrent(s,observer,http);
        parentLockWait(s,observer,http);
        lateFailureRollsBack(s,observer,http);
    }
    private static void successfulAndRepeated(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        order(s,101,new int[][]{{2,3},{1,2},{1,4}});
        int first=stock(s,o,1),second=stock(s,o,2);
        try(var trace=s.openRuntimeTrace()) {
            trace.start();var response=cancel(s,h,1,101);var observed=trace.stop();
            status(s,"actual shop cancellation succeeds",response,200);s.expectField("cancellation keeps the old state response",response,"state","CANCELLED");
            saveTrace(s,observed,"shop-cancel-trace.json");
            var reads=observed.rawStatements().stream().map(ShopCancellationChecks::sql).filter(q->q.startsWith("select")&&q.matches(".*\\bfrom order_line\\b.*")).toList();
            s.check("one bounded server-line source closes before the body",reads.size()==2 && reads.stream()
                .filter(q->q.matches(".*order by product_id asc\\s*,\\s*id asc limit 101")).count()==1,"existence plus one bounded source; complete trace in shop-cancel-trace.json");
        }
        s.check("all quantities including repeated/off-shelf products return once",stock(s,o,1)==first+6 && stock(s,o,2)==second+3,state(s,o).toString());
        var before=state(s,o);error(s,"a second cancellation is still a conflict",cancel(s,h,1,101),409,"NotCancellable");
        unchanged(s,o,before,"repeat cancellation never adds stock again");
        s.fixtureStatement("ALTER TABLE purchase_order AUTO_INCREMENT=10000");
        var created=h.postJson(s.baseUrl()+"/api/orders",bearer(1),"{\"productId\":3,\"quantity\":2}");status(s,"actual BuyNow still creates a quantity order",created,201);
        if(created.statusCode()==201) {
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.body());long id=json.get("id").longValue();
            status(s,"a genuinely bought order can be cancelled",cancel(s,h,1,id),200);
            s.check("buy then cancel restores the same product stock",stock(s,o,3)==10,state(s,o).toString());
        }
    }
    private static void rejectionAndBounds(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        order(s,102,new int[][]{{1,1}});var before=state(s,o);
        status(s,"anonymous cancellation remains unauthorized",h.postJson(s.baseUrl()+CANCEL.replace("{id}","102"),Map.of(),"{}"),401);
        error(s,"foreign order remains forbidden",cancel(s,h,2,102),403,"NotYourOrder");unchanged(s,o,before,"authorization failures never write");
        for(String state:List.of("PAID","SHIPPED")) {
            s.fixtureStatement("UPDATE purchase_order SET state='"+state+"' WHERE id=102");before=state(s,o);
            error(s,state+" cannot be cancelled",cancel(s,h,1,102),409,"NotCancellable");unchanged(s,o,before,state+" rejection never writes");
        }
        order(s,103,new int[][]{});before=state(s,o);error(s,"missing lines are inconsistent",cancel(s,h,1,103),409,"InconsistentOrder");unchanged(s,o,before,"missing lines roll back parent state");
        for(int quantity:List.of(0,-1)) {
            long id=quantity==0?104:105;order(s,id,new int[][]{{1,quantity}});before=state(s,o);
            error(s,"invalid server quantity is rejected",cancel(s,h,1,id),409,"InconsistentOrder");unchanged(s,o,before,"invalid quantity rolls back status");
        }
        order(s,106,new int[][]{{4,1}});before=state(s,o);error(s,"full stock has a declared conflict",cancel(s,h,1,106),409,"StockLimit");unchanged(s,o,before,"overflow never saturates inventory");
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(s.baseUrl()+"/api/orders/106/cancel"))
            .timeout(java.time.Duration.ofSeconds(10)).header("Authorization",bearer(1).get("Authorization"))
            .header("X-Fixture-Non-Strict","true").header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
        var nonStrict=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        s.check("the same protected request really used a non-strict session",nonStrict.headers().firstValue("X-Fixture-Sql-Mode").orElse("").equals("non-strict"),nonStrict.headers().map().toString());
        error(s,"non-strict full stock is still rejected",new HttpAssertionClient.Response(nonStrict.statusCode(),nonStrict.body()),409,"StockLimit");unchanged(s,o,before,"non-strict overflow never saturates inventory");
        order(s,107,IntStream.range(0,100).mapToObj(i->new int[]{1,1}).toArray(int[][]::new));int initial=stock(s,o,1);
        status(s,"the exact hundred-line bound is accepted",cancel(s,h,1,107),200);s.check("all hundred lines are restored",stock(s,o,1)==initial+100,state(s,o).toString());
        order(s,108,IntStream.range(0,101).mapToObj(i->new int[]{1,1}).toArray(int[][]::new));before=state(s,o);
        error(s,"the sentinel rejects 101 lines",cancel(s,h,1,108),409,"OrderTooLarge");unchanged(s,o,before,"over-limit rejects before any body and rolls back parent");
    }
    private static void concurrent(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        order(s,109,new int[][]{{1,2}});int first=stock(s,o,1);
        var responses=parallel(IntStream.range(0,8).<Callable<HttpAssertionClient.Response>>mapToObj(i->()->cancel(s,h,1,109)).toList());
        s.check("eight duplicate cancellations have exactly one winner",count(responses,200)==1 && count(responses,409)==7,statuses(responses));
        s.check("concurrent duplicate returns quantity once",stock(s,o,1)==first+2,state(s,o).toString());
        order(s,110,new int[][]{{1,3}});first=stock(s,o,1);
        responses=parallel(List.of(()->cancel(s,h,1,110),()->h.postJson(s.baseUrl()+PAY.replace("{id}","110"),bearer(1),"{}")));
        s.check("Pay and Cancel cannot both win CREATED",count(responses,200)==1 && count(responses,409)==1,statuses(responses));
        boolean cancelled=orderState(s,o,110).equals("CANCELLED");
        s.check("the winning state decides whether inventory returns",stock(s,o,1)==first+(cancelled?3:0),state(s,o).toString());
        order(s,111,new int[][]{{2,1},{1,1}});order(s,112,new int[][]{{1,2},{2,2}});first=stock(s,o,1);int second=stock(s,o,2);
        responses=parallel(List.of(()->cancel(s,h,1,111),()->cancel(s,h,1,112)));
        s.check("opposite insertion orders use one product lock order",count(responses,200)==2,statuses(responses));
        s.check("both reverse orders fully restore inventory",stock(s,o,1)==first+3&&stock(s,o,2)==second+3,state(s,o).toString());
    }
    private static void parentLockWait(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        order(s,113,new int[][]{{1,1}});int first=stock(s,o,1);
        s.fixtureStatement("START TRANSACTION");s.fixtureStatement("SELECT id FROM purchase_order WHERE id=113 FOR UPDATE");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var request=pool.submit(()->cancel(s,h,1,113));boolean waiting=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end) {
                if(s.fixtureScalar("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l "
                    +"ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='purchase_order'")>0){waiting=true;break;}
                Thread.sleep(30);
            }
            s.check("cancellation observably waits on its parent before deciding",waiting,"performance_schema purchase_order wait");
            s.fixtureStatement("UPDATE purchase_order SET state='PAID' WHERE id=113");s.fixtureStatement("COMMIT");
            error(s,"after waiting the committed state is rechecked",request.get(20,TimeUnit.SECONDS),409,"NotCancellable");
            s.check("a waited-on paid order never restores stock",stock(s,o,1)==first && orderState(s,o,113).equals("PAID"),state(s,o).toString());
        } finally {s.fixtureStatement("ROLLBACK");}
    }
    private static void lateFailureRollsBack(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        order(s,114,new int[][]{{2,1},{1,1}});var before=state(s,o);
        s.fixtureStatement("CREATE TRIGGER fixture_late_shop BEFORE UPDATE ON shop_product FOR EACH ROW BEGIN "
            +"IF NEW.id=2 AND NEW.stock>OLD.stock THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture late stock failure'; END IF; END");
        try(var trace=s.openRuntimeTrace()) {
            trace.start();status(s,"a later product database failure is not swallowed",cancel(s,h,1,114),500);var observed=trace.stop();
            saveTrace(s,observed,"shop-late-rollback-trace.json");
            s.check("both product writes were reached before rollback",observed.rawStatements().stream().map(ShopCancellationChecks::sql).filter(q->q.startsWith("update shop_product ")).count()==2,"complete trace in shop-late-rollback-trace.json");
            unchanged(s,o,before,"later failure rolls back parent, all prior stock deltas and unchanged lines");
        } finally {s.fixtureStatement("DROP TRIGGER fixture_late_shop");}
    }
    static String classify(String digest) {
        String q=sql(digest);
        if(GroupedSumReadBudget.isSessionProbe(q))return "session";
        if(java.util.regex.Pattern.compile("\\bselect\\b").matcher(q).results().count()!=1
            || java.util.regex.Pattern.compile("\\b(join|union|into)\\b").matcher(q).find())return "unexpected";
        for(String table:List.of("access_token","user","purchase_order","order_line","shop_product"))
            if(q.matches("select\\b.+\\bfrom "+table+"\\b.*"))return table;
        return "unexpected";
    }
    private static void saveTrace(BusinessSliceHarness s,MysqlRuntimeTrace.Trace trace,String name) throws Exception {
        var classes=trace.selectDigests().stream().map(q->Map.of("sql",q,"role",classify(q))).toList();
        Files.createDirectories(s.evidenceRoot());new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(s.evidenceRoot().resolve(name).toFile(),Map.of("selectCount",trace.selectCount(),"digests",classes,"rawStatements",trace.rawStatements(),"transactions",trace.transactions(),"readOnlyTransactions",trace.readOnlyTransactions()));
        s.check("shop SQL evidence accounts for every captured read",trace.selectCount()==classes.size()&&classes.stream().noneMatch(q->q.get("role").equals("unexpected")),name);
    }
    private static String sql(String text){return text.replace("`","").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").replaceFirst(";$","");}
    private static void order(BusinessSliceHarness s,long id,int[][] lines) throws Exception {
        s.fixtureStatement("INSERT INTO purchase_order(id,buyer_id,state,total,created_at) VALUES("+id+",1,'CREATED',1.00,'2030-01-01 00:00:00')");
        if(lines.length>0)s.fixtureStatement("INSERT INTO order_line(purchase_id,product_id,quantity,unit_price) VALUES"+
            Arrays.stream(lines).map(line->"("+id+","+line[0]+","+line[1]+",1.00)").collect(java.util.stream.Collectors.joining(",")));
    }
    private static List<String> state(BusinessSliceHarness s,MysqlObserver o) throws Exception {
        var rows=new ArrayList<String>();for(String table:List.of("purchase_order","order_line","shop_product"))for(String row:s.rowsOf(o,table))rows.add(table+":"+row);return List.copyOf(rows);
    }
    private static int stock(BusinessSliceHarness s,MysqlObserver o,int product) throws Exception {return s.rowsOf(o,"shop_product").stream().map(r->r.split("\\|",-1)).filter(r->r[0].equals(Integer.toString(product))).mapToInt(r->Integer.parseInt(r[4])).findFirst().orElseThrow();}
    private static String orderState(BusinessSliceHarness s,MysqlObserver o,int id) throws Exception {return s.rowsOf(o,"purchase_order").stream().map(r->r.split("\\|",-1)).filter(r->r[0].equals(Integer.toString(id))).findFirst().orElseThrow()[2];}
    private static void unchanged(BusinessSliceHarness s,MysqlObserver o,List<String> before,String why) throws Exception {var after=state(s,o);s.check(why,before.equals(after),after.toString());}
    private static Map<String,String> bearer(int actor){return Map.of("Authorization","Bearer 00000000-0000-4000-8000-"+String.format(Locale.ROOT,"%012d",actor));}
    private static HttpAssertionClient.Response cancel(BusinessSliceHarness s,HttpAssertionClient h,int actor,long id) throws Exception {return h.postJson(s.baseUrl()+CANCEL.replace("{id}",Long.toString(id)),bearer(actor),"{}");}
    private static void status(BusinessSliceHarness s,String why,HttpAssertionClient.Response response,int status){s.record(why,response);s.expectStatus(why,response,status);}
    private static void error(BusinessSliceHarness s,String why,HttpAssertionClient.Response response,int status,String code){status(s,why,response,status);s.expectField(why+" code",response,"code",code);}
    private static long count(List<HttpAssertionClient.Response> responses,int status){return responses.stream().filter(r->r.statusCode()==status).count();}
    private static String statuses(List<HttpAssertionClient.Response> responses){return responses.stream().map(HttpAssertionClient.Response::statusCode).toList().toString();}
    private static List<HttpAssertionClient.Response> parallel(List<Callable<HttpAssertionClient.Response>> tasks) throws Exception {
        var gate=new CyclicBarrier(tasks.size());try(var pool=Executors.newFixedThreadPool(tasks.size())) {
            var gated=tasks.stream().<Callable<HttpAssertionClient.Response>>map(task->()->{gate.await(5,TimeUnit.SECONDS);return task.call();}).toList();
            var result=new ArrayList<HttpAssertionClient.Response>();for(var future:pool.invokeAll(gated,25,TimeUnit.SECONDS))result.add(future.get());return List.copyOf(result);
        }
    }
}
