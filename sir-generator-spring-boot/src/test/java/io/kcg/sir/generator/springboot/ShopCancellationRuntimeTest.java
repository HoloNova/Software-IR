package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The actual shop, not a substitute workflow. A proxy is not transaction/rollback evidence. */
class ShopCancellationRuntimeTest {
    static URLClassLoader loader;
    @BeforeAll static void compile(@TempDir Path output) throws Exception {
        String source=Files.readString(Path.of(System.getProperty("basedir",".")).resolveSibling("sir-toolchain-application")
            .resolve("src/test/resources/topics/shop/project.sir"));
        loader=GeneratedRuntimeSupport.compile(source,output);
        var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"shop-cancel");
        assistant.setCurrentNamespace("shop.cancel");
        for(String entity:List.of("Product","PurchaseOrder","OrderLine"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,type("domain."+entity));
    }
    @AfterAll static void close() throws Exception { if(loader!=null)loader.close(); }

    @Test void cancellationRestoresAllServerLinesAndKeepsTheOldPublicResponse() throws Exception {
        var state=new State(2,3);
        var response=state.cancel(1L);
        assertEquals(15,state.stock);
        assertEquals("CANCELLED",response.getClass().getMethod("getState").invoke(response).toString());
        assertEquals(2,state.productWrites);
        assertEquals(List.of("PurchaseOrder.lock","PurchaseOrder.update","OrderLine.exists","OrderLine.source",
            "Product.lock","Product.update","Product.refresh","Product.lock","Product.update","Product.refresh"),state.events);
        assertEquals("CANCELLED",state.storedState);
    }

    @Test void repeatedCancellationCannotRestoreTheStockAgain() throws Exception {
        var state=new State(2);state.cancel(1L);int stock=state.stock,writes=state.productWrites;
        failure("NotCancellableException",()->state.cancel(1L));assertEquals(stock,state.stock);assertEquals(writes,state.productWrites);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PAID","SHIPPED","CANCELLED"})
    void nonCreatedOrdersNeverEnterTheSource(String orderState) throws Exception {
        var state=new State(2);state.storedState=orderState;failure("NotCancellableException",()->state.cancel(1L));
        assertFalse(state.events.contains("OrderLine.source"));assertEquals(0,state.productWrites);
    }
    @Test void foreignBuyerFailsBeforeTheStatusWrite() throws Exception {
        var state=new State(2);failure("NotYourOrderException",()->state.cancel(99L));
        assertEquals(List.of("PurchaseOrder.lock"),state.events);assertEquals("CREATED",state.storedState);
    }
    @Test void missingAndOverflowSourcesNeverLoadAProduct() throws Exception {
        var empty=new State();failure("InconsistentOrderException",()->empty.cancel(1L));
        var huge=new State(java.util.Collections.nCopies(101,1).toArray(Integer[]::new));failure("OrderTooLargeException",()->huge.cancel(1L));
        assertEquals(0,empty.productWrites);assertEquals(0,huge.productWrites);assertFalse(huge.events.contains("Product.lock"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={0,-1})
    void invalidServerQuantitiesFailBeforeLoadingAProduct(int quantity) throws Exception {
        var state=new State(quantity);failure("InconsistentOrderException",()->state.cancel(1L));
        assertFalse(state.events.contains("Product.lock"));assertEquals(0,state.productWrites);
    }
    @Test void absentProductsAndUnsafeStoredStockHaveDeclaredErrors() throws Exception {
        var absent=new State(1);absent.missingProduct=true;failure("ProductNotFoundException",()->absent.cancel(1L));
        for(int stock:List.of(-1,Integer.MAX_VALUE)) {
            var state=new State(1);state.stock=stock;failure("StockLimitException",()->state.cancel(1L));assertEquals(stock,state.stock);assertEquals(0,state.productWrites);
        }
    }
    @Test void aLaterDatabaseErrorPropagatesInsteadOfBeingSwallowedOrContinued() throws Exception {
        var state=new State(1,2);state.failAtWrite=2;failure("DataIntegrityViolationException",()->state.cancel(1L));
        assertEquals(1,state.productWrites,"only the first write reached this proxy; rollback still needs real SQL");
    }
    interface Invocation {void call() throws Exception;}
    static void failure(String expected,Invocation invocation) {
        var error=assertThrows(InvocationTargetException.class,invocation::call);assertEquals(expected,error.getCause().getClass().getSimpleName());
    }

    static final class State {
        final List<Integer> quantities;final List<String> events=new ArrayList<>();
        int stock=10,productWrites,lineCursor,failAtWrite;String storedState="CREATED";boolean missingProduct,failWrite;
        State(Integer... quantities){this.quantities=List.of(quantities);}
        Object cancel(Long actor) throws Exception {
            var serviceType=type("application.CancelOrderService");var ctor=serviceType.getConstructors()[0];
            var deps=new ArrayList<Object>();
            for(Class<?> mapper:ctor.getParameterTypes()) {
                String entity=mapper.getSimpleName().replace("Mapper","");
                deps.add(Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->call(entity,m.getName(),a)));
            }
            var service=ctor.newInstance(deps.toArray());var input=type("api.OrderRef").getConstructor().newInstance();set(input,"Id",51L);
            return serviceType.getMethod("cancelOrder",Long.class,input.getClass()).invoke(service,actor,input);
        }
        Object call(String entity,String method,Object[] args) throws Exception {
            if(entity.equals("PurchaseOrder") && (method.equals("selectOne") || method.equals("selectById"))) {
                events.add("PurchaseOrder.lock");
                var value=type("domain.PurchaseOrder").getConstructor().newInstance();
                set(value,"Id",51L);set(value,"BuyerId",1L);set(value,"State",state("OrderState",storedState));
                set(value,"Total",new java.math.BigDecimal("2.00"));set(value,"CreatedAt",Instant.parse("2030-01-01T00:00:00Z"));return value;
            }
            if(entity.equals("PurchaseOrder") && method.startsWith("update")) {
                events.add("PurchaseOrder.update");if(!storedState.equals("CREATED"))return 0;storedState="CANCELLED";return 1;
            }
            if(entity.equals("OrderLine") && method.equals("selectObjs")){events.add("OrderLine.exists");return quantities.isEmpty()?List.of():List.of(1L);}
            if(entity.equals("OrderLine") && method.equals("selectList")) {
                events.add("OrderLine.source");var wrapper=(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)args[0];
                assertTrue(wrapper.getSqlSegment().contains("ORDER BY product_id ASC,id ASC"),wrapper.getSqlSegment());
                assertTrue(wrapper.getSqlSegment().endsWith("LIMIT 101"),wrapper.getSqlSegment());
                var rows=new ArrayList<Object>();int id=0;
                for(int quantity:quantities){var row=type("domain.OrderLine").getConstructor().newInstance();set(row,"Id",(long)++id);set(row,"PurchaseId",51L);set(row,"ProductId",7L);set(row,"Quantity",quantity);set(row,"UnitPrice",new java.math.BigDecimal("1.00"));rows.add(row);}return rows;
            }
            // The order carries no coupon, so the redemption source answers with nothing and the coupon is never touched.
            if(entity.equals("Redemption") && method.equals("selectList"))return List.of();
            if(entity.equals("Product") && method.equals("selectOne")) {
                events.add("Product.lock");if(missingProduct)return null;
                return product();
            }
            if(entity.equals("Product") && method.equals("selectById")){events.add("Product.refresh");return product();}
            if(entity.equals("Product") && method.equals("update")) {
                events.add("Product.update");int quantity=quantities.get(lineCursor++);
                if(failWrite || failAtWrite==lineCursor)throw new org.springframework.dao.DataIntegrityViolationException("late fixture failure");
                if(stock<0 || stock>Integer.MAX_VALUE-quantity)return 0;
                var wrapper=(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>)args[1];
                assertEquals("stock=stock+"+quantity,wrapper.getSqlSet().replaceAll("\\s+",""));
                stock+=quantity;productWrites++;return 1;
            }
            throw new AssertionError(entity+"."+method);
        }
        Object product() throws Exception {
            var row=type("domain.Product").getConstructor().newInstance();set(row,"Id",7L);set(row,"Stock",stock);
            set(row,"Name","off-shelf");set(row,"Price",new java.math.BigDecimal("1.00"));set(row,"State",state("ProductState","OFF_SHELF"));return row;
        }
    }
    static Class<?> type(String name) throws Exception {return loader.loadClass("example.shop."+name);}
    static Object state(String name,String value) throws Exception {return Enum.valueOf(type("domain."+name).asSubclass(Enum.class),value);}
    static void set(Object bean,String field,Object value) throws Exception {bean.getClass().getMethod("set"+field,value.getClass()).invoke(bean,value);}
}
