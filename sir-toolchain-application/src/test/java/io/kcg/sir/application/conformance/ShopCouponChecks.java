package io.kcg.sir.application.conformance;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * The real shop coupons over HTTP and an independent MySQL connection: the window, the minimum spend, the limit
 * counted by one guarded statement under concurrency, once per buyer and per order, and the coupon given back when
 * an order is cancelled.
 */
final class ShopCouponChecks {
    private ShopCouponChecks() {}

    static void verify(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        coupon(s,1,"SAVE5",5.00,10.00,"2020-01-01 00:00:00","2099-01-01 00:00:00",1);
        coupon(s,2,"OLD",5.00,0.00,"2020-01-01 00:00:00","2021-01-01 00:00:00",9);
        coupon(s,3,"BIG",5.00,100.00,"2020-01-01 00:00:00","2099-01-01 00:00:00",9);
        order(s,301,1,"20.00");order(s,302,2,"20.00");order(s,303,1,"20.00");

        error(s,"an unknown code is a declared not-found",apply(s,h,1,301,"NOPE"),404,"CouponNotFound");
        error(s,"another buyer's order is refused",apply(s,h,2,301,"SAVE5"),403,"NotYourOrder");
        error(s,"an expired coupon is refused",apply(s,h,1,301,"OLD"),409,"CouponExpired");
        error(s,"an order below the minimum spend is refused",apply(s,h,1,301,"BIG"),409,"MinSpendNotMet");
        s.check("refusals change nothing",total(s,301)==2000 && used(s,1)==0 && count(s,"redemption")==0,s.rowsOf(o,"coupon").toString());

        var applied=apply(s,h,1,301,"SAVE5");status(s,"the coupon is applied to the order",applied,201);
        s.check("the stored total drops by the amount off",total(s,301)==1500 && used(s,1)==1 && count(s,"redemption")==1,s.rowsOf(o,"purchase_order").toString());
        error(s,"the same coupon cannot be used twice on one order",apply(s,h,1,301,"SAVE5"),409,"CouponAlreadyUsed");
        error(s,"a coupon at its limit is refused for another buyer",apply(s,h,2,302,"SAVE5"),409,"CouponExhausted");
        s.check("the refused attempts leave the counter and total alone",used(s,1)==1 && total(s,301)==1500 && total(s,302)==2000,s.rowsOf(o,"coupon").toString());

        status(s,"cancelling the order succeeds",h.postJson(s.baseUrl()+"/api/orders/301/cancel",bearer(1),"{}"),200);
        s.check("cancelling gives the coupon back",used(s,1)==0 && count(s,"redemption")==0,s.rowsOf(o,"coupon").toString());
        status(s,"another buyer can now redeem it",apply(s,h,2,302,"SAVE5"),201);
        s.check("the second buyer's order is discounted",total(s,302)==1500 && used(s,1)==1,s.rowsOf(o,"purchase_order").toString());

        coupon(s,4,"RACE",1.00,0.00,"2020-01-01 00:00:00","2099-01-01 00:00:00",3);
        for(int i=0;i<8;i++) {
            int user=10+i;
            s.fixtureStatement("INSERT INTO user(id,role,name,password_hash) VALUES("+user+",'CUSTOMER','racer-"+user+"','not-a-login-password')");
            s.fixtureStatement("INSERT INTO access_token(id,owner_id,expires_at) VALUES('"+token(user)+"',"+user+",'2099-01-01 00:00:00')");
            s.fixtureStatement("INSERT INTO purchase_order(id,buyer_id,state,total,created_at) VALUES("+(400+i)+","+user+",'CREATED',10.00,'2030-01-01 00:00:00')");
        }
        var responses=parallel(IntStream.range(0,8).<Callable<HttpAssertionClient.Response>>mapToObj(i->()->apply(s,h,10+i,400+i,"RACE")).toList());
        s.check("eight buyers race for three redemptions: exactly three win",count(responses,201)==3 && count(responses,409)==5,statuses(responses));
        s.check("the counter equals the redemptions and never passes the limit",used(s,4)==3 && count(s,"redemption","coupon_id=4")==3,s.rowsOf(o,"coupon").toString());
        s.check("only the winners' orders were discounted",s.fixtureScalar("SELECT COUNT(*) FROM purchase_order WHERE id BETWEEN 400 AND 407 AND total=9.00")==3
            && s.fixtureScalar("SELECT COUNT(*) FROM purchase_order WHERE id BETWEEN 400 AND 407 AND total=10.00")==5,s.rowsOf(o,"purchase_order").toString());
    }

    private static void coupon(BusinessSliceHarness s,int id,String code,double off,double min,String from,String to,int limit) throws Exception {
        s.fixtureStatement("INSERT INTO coupon(id,code,amount_off,min_spend,starts_at,ends_at,total_limit,used) VALUES("+id+",'"+code+"',"+off+","+min+",'"+from+"','"+to+"',"+limit+",0)");
    }
    private static void order(BusinessSliceHarness s,long id,int buyer,String total) throws Exception {
        s.fixtureStatement("INSERT INTO purchase_order(id,buyer_id,state,total,created_at) VALUES("+id+","+buyer+",'CREATED',"+total+",'2030-01-01 00:00:00')");
    }
    /** The stored total in cents, so no decimal text is compared. */
    private static long total(BusinessSliceHarness s,long order) throws Exception {
        return s.fixtureScalar("SELECT CAST(total*100 AS SIGNED) FROM purchase_order WHERE id="+order);
    }
    private static long used(BusinessSliceHarness s,int coupon) throws Exception {return s.fixtureScalar("SELECT used FROM coupon WHERE id="+coupon);}
    private static long count(BusinessSliceHarness s,String table) throws Exception {return s.fixtureScalar("SELECT COUNT(*) FROM "+table);}
    private static long count(BusinessSliceHarness s,String table,String where) throws Exception {return s.fixtureScalar("SELECT COUNT(*) FROM "+table+" WHERE "+where);}
    private static String token(int actor){return "00000000-0000-4000-8000-"+String.format(Locale.ROOT,"%012d",actor);}
    private static Map<String,String> bearer(int actor){return Map.of("Authorization","Bearer "+token(actor));}
    private static HttpAssertionClient.Response apply(BusinessSliceHarness s,HttpAssertionClient h,int actor,long order,String code) throws Exception {
        return h.postJson(s.baseUrl()+"/api/orders/"+order+"/coupon",bearer(actor),"{\"code\":\""+code+"\"}");
    }
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
