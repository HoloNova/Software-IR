package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiplicationRuntimeTest {
    private static URLClassLoader loader;
    @BeforeAll static void compile(@TempDir Path directory) throws Exception {
        loader = GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/multiplication.sir"), directory);
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void moneyTimesAnIntegerIsExactInBothDirections() throws Exception {
        for (String capability : new String[]{"Multiply", "Reverse"}) {
            Object result = execute(capability, input("1.2300", 3, 2, 4, 5L), new AtomicInteger());
            assertEquals(0, new BigDecimal("3.69").compareTo((BigDecimal) get(result, "Amount")));
            assertEquals(capability.equals("Multiply") ? 8 : 10, get(result, "Count"));
            assertEquals(capability.equals("Multiply") ? 10L : 50L, get(result, "Wide"));
        }
    }

    @Test void moneyTimesLongUsesAnExactIntegralConversion() throws Exception {
        Object result = execute("Wide", input("1.2300", 3, 2, 4, 5L), new AtomicInteger());
        assertEquals(0, new BigDecimal("6.15").compareTo((BigDecimal) get(result, "Amount")));
    }

    @Test void zeroAndNegativeMoneyAreNotRoundedOrForcedNonNegative() throws Exception {
        assertEquals(0, ((BigDecimal) get(execute("Multiply", input("-1.2300", 3, 2, 4, 5L), new AtomicInteger()), "Amount"))
                .compareTo(new BigDecimal("-3.69")));
        assertEquals(0, ((BigDecimal) get(execute("Multiply", input("1.2300", 0, 2, 4, 5L), new AtomicInteger()), "Amount")).signum());
    }

    @Test void onlyTheFinalMoneyValueMustFitItsStorageShape() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        var failure = assertThrows(InvocationTargetException.class,
                () -> execute("Multiply", input("0.1250", 3, 2, 4, 5L), writes));
        assertEquals("INVALID_REQUEST", failure.getCause().getClass().getMethod("code").invoke(failure.getCause()));
        assertEquals(0, writes.get());
        Object result = execute("Multiply", input("0.1250", 8, 2, 4, 5L), writes);
        assertEquals(0, ((BigDecimal) get(result, "Amount")).compareTo(BigDecimal.ONE));
        assertEquals(1, writes.get());
    }

    @Test void integerOverflowsFailBeforePersistenceWithoutWrapping() throws Exception {
        for (Object input : new Object[]{input("1.00", 1, 50000, 50000, 1L),
                input("1.00", 1, 1, 1, Long.MAX_VALUE)}) {
            AtomicInteger writes = new AtomicInteger();
            var failure = assertThrows(InvocationTargetException.class, () -> execute("Multiply", input, writes));
            assertInstanceOf(ArithmeticException.class, failure.getCause());
            assertEquals(0, writes.get());
        }
    }

    @Test void missingQuantityProducesAFieldErrorAndNeverInserts() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        var failure = assertThrows(InvocationTargetException.class,
                () -> execute("Multiply", input("1.00", null, 2, 4, 5L), writes));
        assertEquals("ApiException", failure.getCause().getClass().getSimpleName());
        assertEquals("INVALID_REQUEST", failure.getCause().getClass().getMethod("code").invoke(failure.getCause()));
        assertEquals(0, writes.get());
    }

    private static Object input(String price, Integer quantity, Integer left, Integer right, Long wide) throws Exception {
        var type = loader.loadClass("example.multiplication.api.Numbers");
        Object value = type.getConstructor().newInstance();
        type.getMethod("setPrice", BigDecimal.class).invoke(value, new BigDecimal(price));
        type.getMethod("setQuantity", Integer.class).invoke(value, quantity);
        type.getMethod("setLeft", Integer.class).invoke(value, left);
        type.getMethod("setRight", Integer.class).invoke(value, right);
        type.getMethod("setWide", Long.class).invoke(value, wide);
        return value;
    }

    private static Object execute(String capability, Object input, AtomicInteger writes) throws Exception {
        Class<?> mapper = loader.loadClass("example.multiplication.persistence.CalculationMapper");
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{mapper}, (object, method, args) -> {
            if (method.getName().equals("insert")) {
                writes.incrementAndGet();
                args[0].getClass().getMethod("setId", Long.class).invoke(args[0], 1L);
                return 1;
            }
            throw new AssertionError(method);
        });
        Object service = loader.loadClass("example.multiplication.application." + capability + "Service")
                .getConstructor(mapper).newInstance(proxy);
        return service.getClass().getMethod(Character.toLowerCase(capability.charAt(0)) + capability.substring(1), input.getClass())
                .invoke(service, input);
    }
    private static Object get(Object value, String property) throws Exception { return value.getClass().getMethod("get" + property).invoke(value); }
}
