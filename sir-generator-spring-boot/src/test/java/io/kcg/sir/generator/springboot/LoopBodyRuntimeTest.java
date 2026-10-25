package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A loop pass that creates, deletes and accumulates, run on the real generated checkout against in-memory tables: one
 * order gets a line per cart item, its total is added up by the database statement, stock is taken per product, and
 * the cart is emptied. No database is started; the mappers below stand in for the stored rows.
 */
class LoopBodyRuntimeTest {
    @TempDir Path work;

    @Test void checkoutTurnsEveryCartItemIntoALineAndAddsUpTheTotal() throws Exception {
        try (var loader = compile()) {
            var shop = new Shop(loader);
            shop.product(1L, "2.50", 10);
            shop.product(2L, "4.00", 1);
            shop.cart(11L, 1L, 3);
            shop.cart(12L, 2L, 1);

            Object order = shop.checkout();

            assertEquals(0, new BigDecimal("11.50").compareTo((BigDecimal) get(order, "Total")), "3 x 2.50 + 1 x 4.00");
            assertEquals(0, new BigDecimal("11.50").compareTo(shop.storedTotal), "the stored total is the sum of the deltas");
            assertEquals(List.of(7, 0), List.of(shop.stock.get(1L), shop.stock.get(2L)));
            assertEquals(2, shop.lines.size());
            assertEquals(List.of(11L, 12L), shop.deleted, "every cart item was removed");
        }
    }

    @Test void aShortProductStopsTheCheckoutWithItsDeclaredError() throws Exception {
        try (var loader = compile()) {
            var shop = new Shop(loader);
            shop.product(1L, "2.50", 2);
            shop.cart(11L, 1L, 3);

            var error = assertThrows(InvocationTargetException.class, shop::checkout).getCause();
            assertEquals("OutOfStockException", error.getClass().getSimpleName());
        }
    }

    @Test void clearingTheCartDeletesEveryItem() throws Exception {
        try (var loader = compile()) {
            var shop = new Shop(loader);
            shop.cart(11L, 1L, 1);
            shop.cart(12L, 2L, 1);
            var service = loader.loadClass("example.cart.application.ClearCartService");
            Object instance = service.getConstructors()[0].newInstance(shop.mapper("CartItem"));
            service.getMethod("clearCart", Long.class).invoke(instance, 5L);
            assertEquals(List.of(11L, 12L), shop.deleted);
        }
    }

    private URLClassLoader compile() throws Exception {
        return GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/cart-checkout.sir"), work);
    }

    /** Stored rows and the mapper calls that read and change them. */
    private static final class Shop {
        final ClassLoader loader;
        final Map<Long, BigDecimal> price = new HashMap<>();
        final Map<Long, Integer> stock = new HashMap<>();
        final List<Object> cart = new ArrayList<>();
        final List<Object> lines = new ArrayList<>();
        final List<Long> deleted = new ArrayList<>();
        BigDecimal storedTotal;
        final com.baomidou.mybatisplus.core.MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();

        Shop(ClassLoader loader) throws Exception {
            this.loader = loader;
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "cart");
            assistant.setCurrentNamespace("cart.test");
            for (String entity : List.of("Product", "CartItem", "PurchaseOrder", "OrderLine"))
                com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, type("domain." + entity));
        }

        void product(long id, String unitPrice, int count) { price.put(id, new BigDecimal(unitPrice)); stock.put(id, count); }

        void cart(long id, long product, int quantity) throws Exception {
            Object item = type("domain.CartItem").getConstructor().newInstance();
            set(item, "Id", id); set(item, "OwnerId", 5L); set(item, "ProductId", product); set(item, "Quantity", quantity);
            cart.add(item);
        }

        Object checkout() throws Exception {
            var service = type("application.CheckoutService");
            var constructor = service.getConstructors()[0];
            Object instance = constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                    .map(t -> mapper(t.getSimpleName().replace("Mapper", ""))).toArray());
            // Adding to a stored money total first checks the transaction's session runs in strict SQL mode.
            try (var transaction = DecimalSessionProbe.strictTransaction(configuration)) {
                return service.getMethod("checkout", Long.class).invoke(instance, 5L);
            }
        }

        Object mapper(String entity) {
            try {
                var type = type("persistence." + entity + "Mapper");
                return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> call(entity, method.getName(), args));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Object call(String entity, String method, Object[] args) throws Exception {
            switch (entity + "." + method) {
                case "CartItem.selectObjs": return cart.isEmpty() ? List.of() : List.of(1L);
                case "CartItem.selectList": return List.copyOf(cart);
                case "CartItem.deleteById": deleted.add((Long) args[0]); return 1;
                case "PurchaseOrder.insert": set(args[0], "Id", 100L); storedTotal = (BigDecimal) get(args[0], "Total"); return 1;
                case "PurchaseOrder.update": storedTotal = storedTotal.add(delta(args[1])); return 1;
                case "PurchaseOrder.selectById": {
                    Object row = type("domain.PurchaseOrder").getConstructor().newInstance();
                    set(row, "Id", 100L); set(row, "Total", storedTotal);
                    return row;
                }
                case "Product.selectOne": return product(only(args[0], Long.class));
                case "Product.update": {
                    long id = only(args[1], Long.class);
                    int quantity = values(args[1]).stream().filter(Integer.class::isInstance).map(Integer.class::cast).findFirst().orElseThrow();
                    if (stock.get(id) < quantity) return 0;
                    stock.put(id, stock.get(id) - quantity);
                    return 1;
                }
                case "Product.selectById": return product((Long) args[0]);
                case "OrderLine.insert": lines.add(args[0]); return 1;
                default: throw new AssertionError("unexpected mapper call " + entity + "." + method);
            }
        }

        private Object product(long id) throws Exception {
            Object row = type("domain.Product").getConstructor().newInstance();
            set(row, "Id", id); set(row, "Price", price.get(id)); set(row, "Stock", stock.get(id));
            return row;
        }

        private static List<Object> values(Object wrapper) {
            var w = (AbstractWrapper<?, ?, ?>) wrapper;
            w.getSqlSegment();
            return new ArrayList<>(new TreeMap<>(w.getParamNameValuePairs()).values());
        }

        /** The amount a {@code SET total = total + ?} adds, whether bound as a parameter or written into the statement. */
        private static BigDecimal delta(Object wrapper) {
            var w = (AbstractWrapper<?, ?, ?>) wrapper;
            String set = ((com.baomidou.mybatisplus.core.conditions.update.Update<?, ?>) wrapper).getSqlSet();
            var matcher = java.util.regex.Pattern.compile("\\+\\s*(?:#\\{ew\\.paramNameValuePairs\\.(\\w+)[^}]*}|(-?[0-9.]+))").matcher(set);
            assertTrue(matcher.find(), set);
            return matcher.group(1) != null ? (BigDecimal) w.getParamNameValuePairs().get(matcher.group(1)) : new BigDecimal(matcher.group(2));
        }

        private static <T> T only(Object wrapper, Class<T> kind) {
            var all = values(wrapper);
            return all.stream().filter(kind::isInstance).map(kind::cast).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + kind.getSimpleName() + " among " + all + " of " + wrapper));
        }

        Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass("example.cart." + name); }
    }

    private static Object get(Object bean, String property) throws Exception { return bean.getClass().getMethod("get" + property).invoke(bean); }
    private static void set(Object bean, String property, Object value) throws Exception {
        Arrays.stream(bean.getClass().getMethods()).filter(m -> m.getName().equals("set" + property) && m.getParameterCount() == 1)
                .findFirst().orElseThrow().invoke(bean, value);
    }
}
