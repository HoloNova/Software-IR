package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Executes the actual topic's generated scalar services, rather than a lookalike ledger. */
class LedgerSumRuntimeTest {
    @TempDir static Path output;
    static URLClassLoader loader;
    @BeforeAll static void compile() throws Exception {
        Path source = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application")
                .resolve("src/test/resources/topics/ledger/project.sir");
        loader = GeneratedRuntimeSupport.compile(Files.readString(source), output);
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "ledger-sum");
        assistant.setCurrentNamespace("ledger.fixture");
        for (String name : List.of("User", "Category", "Entry"))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, type("domain." + name));
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void totalReadsOnlyActorAndRequestedHalfOpenRangeAndDoesNotApplySingleRowPrecision() throws Exception {
        var queries = new ArrayList<com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>>();
        var expected = new BigDecimal("199999999999999999.98");
        assertEquals(expected, invoke("TotalBetween", "SumRange", true, true, 1L, expected, queries));
        assertEquals(1, queries.size());
        var query = queries.getFirst();
        assertEquals("COALESCE(SUM(`amount`), 0)", ((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>)query).getSqlSelect());
        String where = query.getSqlSegment();
        assertTrue(where.contains("owner_id") && where.contains("occurred_at >=") && where.contains("occurred_at <"), where);
        assertTrue(query.getParamNameValuePairs().containsValue(1L));
        assertTrue(query.getParamNameValuePairs().values().stream().allMatch(v -> v instanceof Long || v instanceof Instant), query.getParamNameValuePairs().toString());
    }
    @Test void missingOrReversedRangeIsRejectedBeforeAnyMapperCall() throws Exception {
        for (boolean missing : List.of(true,false)) {
            var queries = new ArrayList<com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>>();
            var error = assertThrows(InvocationTargetException.class,
                    () -> invoke("TotalBetween", "SumRange", !missing, false, 1L, BigDecimal.ZERO, queries));
            assertEquals("BadRange", error.getCause().getClass().getMethod("code").invoke(error.getCause()));
            assertTrue(queries.isEmpty());
        }
    }
    @Test void categoryTotalsBindTheLoadedCategoryIdentityAndActor() throws Exception {
        var queries = new ArrayList<com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>>();
        assertEquals(new BigDecimal("3.57"), invoke("TotalOfCategory", "CategorySumRange", true, true, 1L, new BigDecimal("3.57"), queries));
        assertEquals(1,queries.size());
        var query=queries.getFirst();query.getSqlSegment();
        assertTrue(query.getParamNameValuePairs().containsValue(10L),query.getParamNameValuePairs().toString());
        assertFalse(query.getParamNameValuePairs().values().stream().anyMatch(v -> v.getClass().getName().contains("domain.Category")));
    }
    @Test void anotherUsersCategoryIsNotAnAggregateOracle() {
        var queries = new ArrayList<com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>>();
        var error = assertThrows(InvocationTargetException.class,
                () -> invoke("TotalOfCategory", "CategorySumRange", true, true, 2L, BigDecimal.TEN, queries));
        assertEquals("CategoryNotFound", assertDoesNotThrow(() -> error.getCause().getClass().getMethod("code").invoke(error.getCause())));
        assertTrue(queries.isEmpty());
    }
    private static Object invoke(String capability,String dto,boolean since,boolean valid,long categoryOwner,BigDecimal total,
            List<com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>> queries) throws Exception {
        Class<?> inputType=type("api."+dto);Object input=inputType.getConstructor().newInstance();
        Instant start=Instant.parse("2026-01-01T00:00:00Z");
        if(since)inputType.getMethod("setSince",Optional.class).invoke(input,Optional.of(start));
        inputType.getMethod("setUntil",Optional.class).invoke(input,Optional.of(valid?start.plusSeconds(86400):start));
        if(dto.equals("CategorySumRange"))inputType.getMethod("setCategoryId",Long.class).invoke(input,10L);
        var service=type("application."+capability+"Service");var constructor=service.getConstructors()[0];
        var args=Arrays.stream(constructor.getParameterTypes()).map(mapper -> Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->{
            if(mapper.getSimpleName().equals("EntryMapper")){
                assertEquals("selectObjs",m.getName());queries.add((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?,?,?>)a[0]);return List.of(total);
            }
            assertEquals("selectById",m.getName());
            var category=type("domain.Category").getConstructor().newInstance();
            category.getClass().getMethod("setId",Long.class).invoke(category,10L);
            category.getClass().getMethod("setOwnerId",Long.class).invoke(category,categoryOwner);return category;
        })).toArray();
        var method=Arrays.stream(service.getMethods()).filter(m -> m.getName().equals(Character.toLowerCase(capability.charAt(0))+capability.substring(1))).findFirst().orElseThrow();
        return method.invoke(constructor.newInstance(args),Arrays.stream(method.getParameterTypes()).map(t -> t==inputType?input:1L).toArray());
    }
    private static Class<?> type(String name) throws ClassNotFoundException{return loader.loadClass("example.ledger."+name);}
}
