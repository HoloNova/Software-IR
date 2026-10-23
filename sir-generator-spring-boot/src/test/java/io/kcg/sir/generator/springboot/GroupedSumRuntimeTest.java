package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.*;

class GroupedSumRuntimeTest {
    @TempDir static Path work;
    static URLClassLoader loader;
    static Class<?> mapper, inputType, serviceType;
    static MybatisConfiguration configuration;

    @BeforeAll static void compileActualService() throws Exception {
        loader = GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/decimal-group.sir"), work);
        mapper = loader.loadClass("example.group.persistence.EntryMapper");
        inputType = loader.loadClass("example.group.api.TotalsInput");
        serviceType = loader.loadClass("example.group.application.TotalsByCategoryService");
        configuration = new MybatisConfiguration();
        var assistant = new MapperBuilderAssistant(configuration, "grouped-runtime");
        assistant.setCurrentNamespace(mapper.getName());
        new DefaultSqlInjector().inspectInject(assistant, mapper);
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"q0","q1","entryMapper"})
    void authoredResultNamesCannotCollideWithGeneratedLambdas(String name) throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/decimal-group.sir")
            .replace("as totals;","as "+name+";").replace("return totals;","return "+name+";");
        try(var compiled=GeneratedRuntimeSupport.compile(source,work.resolve(name))) { assertNotNull(compiled); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ExactAggregate","PageResponse"})
    void entityNamesCannotShadowThePublicGroupCarriers(String entity) throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/decimal-group.sir").replace("Entry",entity);
        try(var compiled=GeneratedRuntimeSupport.compile(source,work.resolve(entity))) {assertNotNull(compiled);}
    }

    @Test void uuidGroupKeysUseTheDeclaredIdentityInsteadOfLongCoercion() throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/decimal-group.sir").replace("\r\n","\n")
            .replace("entity Category persistent {\n      identity id: Id generated auto;",
                     "entity Category persistent {\n      identity id: Token generated uuid;");
        try(var compiled=GeneratedRuntimeSupport.compile(source,work.resolve("uuid"))) {
            var type=compiled.loadClass("example.group.application.TotalsByCategoryService");
            assertTrue(type.getMethod("totalsByCategory",Long.class,compiled.loadClass("example.group.api.TotalsInput"))
                .getGenericReturnType().getTypeName().contains("java.util.UUID"));
            var helper=compiled.loadClass("example.group.persistence.ExactAggregate");
            var id=java.util.UUID.randomUUID();
            assertEquals(id,helper.getMethod("uuidKey",Object.class).invoke(null,id.toString()));
            assertThrows(InvocationTargetException.class,()->helper.getMethod("uuidKey",Object.class).invoke(null,1L));
        }
    }

    @Test void explicitReadOnlySnapshotIsOnTheActualService() {
        var tx = serviceType.getAnnotation(Transactional.class);
        assertTrue(tx.readOnly()); assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
    }

    @Test void exactPageUsesOneWrapperAndTwoQueriesWithTheSameEvaluatedFilter() throws Exception {
        var probe = new Probe(3L, List.of(row(1L, "-1.2300"), row(4L, "199999999999999999.9800")));
        Object response = invoke(probe, 1, 2);
        assertEquals(List.of("selectCount", "selectMaps"), probe.calls);
        assertEquals(3L, read(response, "total"));
        assertEquals(1, read(response, "page")); assertEquals(2, read(response, "size"));
        var records = (List<?>) read(response, "records");
        assertEquals(4L, read(records.get(1), "key"));
        assertEquals(new BigDecimal("199999999999999999.9800"), read(records.get(1), "total"));
        assertTrue(probe.sql.get(0).contains("COUNT( DISTINCT `category_id` )"), probe.sql.toString());
        assertFalse(probe.sql.get(0).contains("GROUP BY"));
        assertTrue(probe.sql.get(1).contains("GROUP BY `category_id` ORDER BY `category_id` ASC LIMIT 2 OFFSET 0"), probe.sql.toString());
        assertTrue(probe.sql.get(1).contains("category_rel.id = entry.category_id"), probe.sql.toString());
        assertTrue(probe.sql.get(1).contains("category_rel.owner_id") && probe.sql.get(1).contains("category_rel.removed"));
        assertTrue(probe.values.get(0).contains(42L));
        assertEquals(probe.values.get(0), probe.values.get(1));
    }

    @Test void emptyInputRetainsPaginationDefaultsAndAnEmptyGroupPage() throws Exception {
        var probe = new Probe(0L, List.of());
        Object response = invoke(probe, null, null);
        assertEquals(0L, read(response, "total")); assertEquals(List.of(), read(response, "records"));
        assertEquals(1, read(response, "page")); assertEquals(20, read(response, "size"));
        assertEquals(2, probe.calls.size());
    }

    @Test void beyondLastPagePreservesGroupCountAndReturnsNoGroups() throws Exception {
        Object response = invoke(new Probe(2L, List.of()), 10, 2);
        assertEquals(2L, read(response, "total")); assertEquals(List.of(), read(response, "records"));
    }

    @Test void invalidPageIsRefusedBeforeEitherQuery() {
        var probe = new Probe(0L, List.of());
        var error = assertThrows(InvocationTargetException.class, () -> invoke(probe, 0, 1));
        assertEquals("BadPageException", error.getCause().getClass().getSimpleName());
        assertTrue(probe.calls.isEmpty());
    }

    @Test void malformedGroupCarrierCannotBecomeAnUntrustedResponse() {
        for (var row : List.of(Map.of("g_key", (Object)1L, "g_total", 1.1d),
                Map.of("g_key", (Object)1, "g_total", BigDecimal.ZERO),
                Map.of("g_key", (Object)1L, "g_total", BigDecimal.ZERO, "extra", 1))) {
            var error = assertThrows(InvocationTargetException.class, () -> invoke(new Probe(1L, List.of(row)), 1, 2));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
    }

    @Test void aCountFailureNeverReadsOrReturnsAPartialPage() {
        var probe = new Probe(null, List.of());
        assertThrows(InvocationTargetException.class, () -> invoke(probe, 1, 2));
        assertEquals(List.of("selectCount"), probe.calls);
    }

    private static Map<String,Object> row(long id, String amount) {
        return Map.of("g_key", id, "g_total", new BigDecimal(amount));
    }
    private static Object read(Object object, String property) throws Exception {
        String getter = object.getClass().isRecord() ? property : "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return object.getClass().getMethod(getter).invoke(object);
    }
    private static Object invoke(Probe probe, Integer page, Integer size) throws Exception {
        Object input = inputType.getConstructor().newInstance();
        inputType.getMethod("setPage", Integer.class).invoke(input, page);
        inputType.getMethod("setSize", Integer.class).invoke(input, size);
        Object mapperProxy = Proxy.newProxyInstance(loader, new Class<?>[]{mapper}, probe);
        Object service = serviceType.getConstructor(mapper).newInstance(mapperProxy);
        return serviceType.getMethod("totalsByCategory", Long.class, inputType).invoke(service, 42L, input);
    }
    private static final class Probe implements InvocationHandler {
        final Long total;
        final List<Map<String,Object>> rows;
        final List<String> calls = new ArrayList<>(), sql = new ArrayList<>();
        final List<List<Object>> values = new ArrayList<>();
        Object wrapper;
        Probe(Long total, List<Map<String,Object>> rows) { this.total=total; this.rows=rows; }
        public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            assertTrue(Set.of("selectCount", "selectMaps").contains(method.getName()), method.getName());
            if (wrapper == null) wrapper=args[0]; else assertSame(wrapper, args[0]);
            assertTrue(args[0] instanceof QueryWrapper<?>);
            var mapped=configuration.getMappedStatement(mapper.getName()+"."+method.getName());
            var parameters=Map.of("ew", args[0]);
            var bound=mapped.getBoundSql(parameters);
            calls.add(method.getName()); sql.add(bound.getSql().replaceAll("\\s+", " ").trim());
            List<Object> bindings=new ArrayList<>();
            var jdbc=(java.sql.PreparedStatement)Proxy.newProxyInstance(loader, new Class<?>[]{java.sql.PreparedStatement.class},
                (p,m,a) -> { bindings.add(m.getName().equals("setNull") ? null : a[1]); return null; });
            new DefaultParameterHandler(mapped, parameters, bound).setParameters(jdbc);
            values.add(bindings);
            return method.getName().equals("selectCount") ? total : rows;
        }
    }
}
