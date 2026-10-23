package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ExactAggregateWarningRuntimeTest {
    @TempDir static Path work;
    static URLClassLoader loader;
    static Class<?> helper;
    @BeforeAll static void compile() throws Exception {
        loader = GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/decimal-sum.sir"), work);
        helper = loader.loadClass("example.sum.persistence.ExactAggregate");
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void markedStatementDiscardsOnlyStaleWarningsAndChecksItsOwnWarningBeforeReturning() throws Throwable {
        var calls = new ArrayList<String>();
        assertEquals(List.of(BigDecimal.ZERO), run(true, null, false, calls));
        assertEquals(List.of("clear", "execute", "warnings"), calls);
    }
    @Test void aWarningIncludingAnOverflowInItsChainNeverReturnsATruncatedValue() throws Throwable {
        SQLWarning warning = new SQLWarning("fixture warning", "01000");
        warning.setNextWarning(new SQLWarning("fixture overflow", "22003", 1292));
        var calls = new ArrayList<String>();
        var failure = assertThrows(SQLDataException.class, () -> run(true, warning, false, calls));
        assertEquals("22003", failure.getSQLState());
        assertSame(warning, failure.getCause());
        assertFalse(failure.getMessage().contains("fixture"));
        assertEquals(List.of("clear", "execute", "warnings"), calls);
    }
    @Test void ordinaryNamedMapperArgumentsNeedNotContainAWrapper() throws Throwable {
        var parameters = new org.apache.ibatis.binding.MapperMethod.ParamMap<Object>();
        parameters.put("id", 1L);
        var calls = new ArrayList<String>();
        assertEquals(List.of(BigDecimal.ZERO), run(false, null, false, calls, parameters));
        assertEquals(List.of("execute"), calls);
    }
    @Test void ordinaryQueriesDoNotAcquireANewWarningPolicy() throws Throwable {
        var calls = new ArrayList<String>();
        assertEquals(List.of(BigDecimal.ZERO), run(false, new SQLWarning("ordinary"), false, calls));
        assertEquals(List.of("execute"), calls);
    }
    @Test void failureToReadWarningsCannotBeSilentlyTreatedAsSuccess() {
        assertThrows(SQLException.class, () -> run(true, null, true, new ArrayList<>()));
    }
    @Test void resultCardinalityAndDecimalCarrierAreStrictButSingleValuePrecisionIsNotApplied() throws Exception {
        var value = helper.getMethod("value", List.class);
        for (Object invalid : List.of(List.of(), List.of(1L), List.of(1.0), List.of(BigDecimal.ONE, BigDecimal.TEN))) {
            var error = assertThrows(InvocationTargetException.class, () -> value.invoke(null, invalid));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
        BigDecimal huge = new BigDecimal("-999999999999999999999999999999999999.123400");
        assertEquals(huge, value.invoke(null, List.of(huge)));
    }
    private static Object run(boolean marked, SQLWarning ownWarning, boolean cannotRead, List<String> calls) throws Throwable {
        return run(marked, ownWarning, cannotRead, calls, null);
    }
    private static Object run(boolean marked, SQLWarning ownWarning, boolean cannotRead, List<String> calls, Map<String,Object> parameters) throws Throwable {
        Object wrapper = marked ? helper.getMethod("query", Class.class, String.class)
                .invoke(null, loader.loadClass("example.sum.domain.Entry"), "COALESCE(SUM(`amount`), 0)")
                : new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        var warning = new AtomicReference<>(new SQLWarning("stale warning must not pollute a new aggregate"));
        var statement = (Statement) Proxy.newProxyInstance(loader, new Class<?>[]{Statement.class}, (p,m,a) -> {
            return switch (m.getName()) {
                case "clearWarnings" -> { calls.add("clear"); warning.set(null); yield null; }
                case "getWarnings" -> { calls.add("warnings"); if (cannotRead) throw new SQLException("fixture diagnostics failure"); yield warning.get(); }
                default -> throw new AssertionError("unexpected Statement operation: " + m.getName());
            };
        });
        var parameter = (ParameterHandler) Proxy.newProxyInstance(loader, new Class<?>[]{ParameterHandler.class},
                (p,m,a) -> { assertEquals("getParameterObject", m.getName()); return parameters == null ? Map.of("ew", wrapper) : parameters; });
        var handler = (StatementHandler) Proxy.newProxyInstance(loader, new Class<?>[]{StatementHandler.class}, (p,m,a) -> {
            return switch (m.getName()) {
                case "getParameterHandler" -> parameter;
                case "query" -> { calls.add("execute"); warning.set(ownWarning); yield List.of(BigDecimal.ZERO); }
                default -> throw new AssertionError("unexpected handler operation: " + m.getName());
            };
        });
        var invocation = new Invocation(handler, StatementHandler.class.getMethod("query", Statement.class,
                org.apache.ibatis.session.ResultHandler.class), new Object[]{statement, null});
        return ((Interceptor) helper.getConstructor().newInstance()).intercept(invocation);
    }
}
