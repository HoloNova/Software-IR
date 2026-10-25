package io.kcg.sir.application.conformance;

import java.util.*;

/**
 * The real shop cart over HTTP and an independent MySQL connection: one checkout turns every cart row of a buyer into
 * one order with a line per product, the stored total is the sum of the lines, stock is taken per product and the cart
 * is emptied; a product that runs short rolls the whole checkout back.
 */
final class ShopCheckoutChecks {
    private static final String CART="/api/cart",CHECKOUT="/api/cart/checkout",CLEAR="/api/cart/clear";
    private ShopCheckoutChecks() {}

    static void verify(BusinessSliceHarness s,MysqlObserver o,HttpAssertionClient h) throws Exception {
        s.fixtureStatement("INSERT INTO shop_product (id,name,price,stock,state) VALUES(20,'cart-a',2.50,5,'ON_SHELF'),(21,'cart-b',4.00,1,'ON_SHELF')");
        status(s,"a product goes into the cart",add(s,h,20,3),201);
        status(s,"a second product goes into the cart",add(s,h,21,1),201);
        error(s,"the same product twice is a declared conflict",add(s,h,20,1),409,"AlreadyInCart");
        error(s,"an off-shelf product cannot be added",add(s,h,1,1),404,"ProductNotFound");
        status(s,"the buyer sees the cart",h.get(s.baseUrl()+CART+"?page=1&size=10",bearer(1)),200);
        s.check("two cart rows are stored",s.fixtureScalar("SELECT COUNT(*) FROM cart_item WHERE owner_id=1")==2,s.rowsOf(o,"cart_item").toString());

        var checkout=h.postJson(s.baseUrl()+CHECKOUT,bearer(1),"{}");status(s,"checkout creates one order",checkout,201);
        if(checkout.statusCode()==201) {
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(checkout.body());long id=json.get("id").longValue();
            s.check("the answered total is 3 x 2.50 + 1 x 4.00",json.get("total").decimalValue().compareTo(new java.math.BigDecimal("11.50"))==0,checkout.body());
            s.check("the stored total is the sum of the lines",s.fixtureScalar("SELECT COUNT(*) FROM purchase_order WHERE id="+id+" AND buyer_id=1 AND total=11.50")==1,s.rowsOf(o,"purchase_order").toString());
            s.check("one line per cart row with the product price",s.fixtureScalar("SELECT COUNT(*) FROM order_line WHERE purchase_id="+id
                +" AND ((product_id=20 AND quantity=3 AND unit_price=2.50) OR (product_id=21 AND quantity=1 AND unit_price=4.00))")==2,s.rowsOf(o,"order_line").toString());
        }
        s.check("stock is taken per product",stock(s,20)==2 && stock(s,21)==0,s.rowsOf(o,"shop_product").toString());
        s.check("the cart is empty after checkout",s.fixtureScalar("SELECT COUNT(*) FROM cart_item WHERE owner_id=1")==0,s.rowsOf(o,"cart_item").toString());
        error(s,"an empty cart cannot be checked out",h.postJson(s.baseUrl()+CHECKOUT,bearer(1),"{}"),409,"CartEmpty");

        status(s,"a product in stock goes into the cart again",add(s,h,20,1),201);
        status(s,"a sold-out product can still sit in the cart",add(s,h,21,1),201);
        long orders=s.fixtureScalar("SELECT COUNT(*) FROM purchase_order"),lines=s.fixtureScalar("SELECT COUNT(*) FROM order_line");
        error(s,"a sold-out product stops the checkout",h.postJson(s.baseUrl()+CHECKOUT,bearer(1),"{}"),409,"OutOfStock");
        s.check("the stock already taken for the first product is rolled back",stock(s,20)==2 && stock(s,21)==0,s.rowsOf(o,"shop_product").toString());
        s.check("no order or line survives the failed checkout",s.fixtureScalar("SELECT COUNT(*) FROM purchase_order")==orders
            && s.fixtureScalar("SELECT COUNT(*) FROM order_line")==lines,s.rowsOf(o,"purchase_order").toString());
        s.check("the cart is kept after the failed checkout",s.fixtureScalar("SELECT COUNT(*) FROM cart_item WHERE owner_id=1")==2,s.rowsOf(o,"cart_item").toString());

        status(s,"another buyer's cart is untouched by clearing",add(s,h,2,20,1),201);
        status(s,"clearing the cart succeeds",h.postJson(s.baseUrl()+CLEAR,bearer(1),"{}"),200);
        s.check("clearing removes only the buyer's rows",s.fixtureScalar("SELECT COUNT(*) FROM cart_item WHERE owner_id=1")==0
            && s.fixtureScalar("SELECT COUNT(*) FROM cart_item WHERE owner_id=2")==1,s.rowsOf(o,"cart_item").toString());
    }

    private static HttpAssertionClient.Response add(BusinessSliceHarness s,HttpAssertionClient h,int product,int quantity) throws Exception {return add(s,h,1,product,quantity);}
    private static HttpAssertionClient.Response add(BusinessSliceHarness s,HttpAssertionClient h,int actor,int product,int quantity) throws Exception {
        return h.postJson(s.baseUrl()+CART,bearer(actor),"{\"productId\":"+product+",\"quantity\":"+quantity+"}");
    }
    private static long stock(BusinessSliceHarness s,int product) throws Exception {return s.fixtureScalar("SELECT stock FROM shop_product WHERE id="+product);}
    private static Map<String,String> bearer(int actor){return Map.of("Authorization","Bearer 00000000-0000-4000-8000-"+String.format(Locale.ROOT,"%012d",actor));}
    private static void status(BusinessSliceHarness s,String why,HttpAssertionClient.Response response,int status){s.record(why,response);s.expectStatus(why,response,status);}
    private static void error(BusinessSliceHarness s,String why,HttpAssertionClient.Response response,int status,String code){status(s,why,response,status);s.expectField(why+" code",response,"code",code);}
}
