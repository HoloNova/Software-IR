package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoneyAggregateFixtureCompilationTest {
    @Test void theActualMoneyItAndItsIsolatedWarningProbeCompileTogether(@TempDir Path output) throws Exception {
        Path resources = Path.of(System.getProperty("basedir", ".")).resolveSibling("sir-toolchain-application").resolve("src/test/resources");
        String source = Files.readString(resources.resolve("valid/money.sir"));
        String probe = Files.readString(resources.resolve("conformance/mysql/ExactAggregateWarningFixture.java.txt"));
        var file = output.resolve("ExactAggregateWarningFixture.java");
        Files.writeString(file, probe);
        var grouped=output.resolve("GroupedSumFixture.java");
        Files.writeString(grouped,Files.readString(resources.resolve("conformance/mysql/GroupedSumFixture.java.txt")));
        try (var loader = GeneratedRuntimeSupport.compile(source, output, List.of(file,grouped))) {
            assertNotNull(loader.loadClass("example.money.fixture.GroupedSumFixture"));
            verifyAfterCountBarrier(loader);
            assertNotNull(loader.loadClass("example.money.application.GroupMeasuredService"));
            assertNotNull(loader.loadClass("example.money.fixture.ExactAggregateWarningFixture"));
            assertNotNull(loader.loadClass("example.money.application.SumMeasuredService"));
            assertNotNull(loader.loadClass("example.money.application.TaggedSumService"));
            assertNotNull(loader.loadClass("example.money.application.LiteralSumService"));
        }
        assertFalse(GeneratorTestSupport.generateSuccessFromText(source).stream().anyMatch(f -> f.relativePath().contains("WarningFixture") || f.relativePath().contains("GroupedSumFixture")),
                "the fixed SQL warning probe is not a generator output");
    }

    private static void verifyAfterCountBarrier(java.net.URLClassLoader loader) throws Exception {
        var mapper=loader.loadClass("example.money.persistence.SummaryEntryMapper");
        Object proxy=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{mapper},(p,m,a)->{throw new AssertionError("no database call in fixture test");});
        var fixtureType=loader.loadClass("example.money.fixture.GroupedSumFixture");
        Object fixture=fixtureType.getConstructor(mapper).newInstance(proxy);
        fixtureType.getMethod("arm").invoke(fixture);
        var helper=loader.loadClass("example.money.persistence.ExactAggregate");
        var wrapper=(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>)helper.getMethod("query",Class.class,String.class)
            .invoke(null,loader.loadClass("example.money.domain.SummaryEntry"),"DISTINCT `category_id`");
        wrapper.eq("owner",99L); wrapper.getSqlSegment();
        var parameter=(org.apache.ibatis.executor.parameter.ParameterHandler)java.lang.reflect.Proxy.newProxyInstance(loader,
            new Class<?>[]{org.apache.ibatis.executor.parameter.ParameterHandler.class},(p,m,a)->java.util.Map.of("ew",wrapper));
        var bound=new org.apache.ibatis.mapping.BoundSql(new org.apache.ibatis.session.Configuration(),
            "SELECT COUNT( DISTINCT `category_id` ) AS total FROM summary_entry WHERE owner=?",java.util.List.of(),java.util.Map.of("ew",wrapper));
        var executed=new java.util.concurrent.atomic.AtomicBoolean();
        var handler=(org.apache.ibatis.executor.statement.StatementHandler)java.lang.reflect.Proxy.newProxyInstance(loader,
            new Class<?>[]{org.apache.ibatis.executor.statement.StatementHandler.class},(p,m,a)->switch(m.getName()) {
                case "getParameterHandler" -> parameter;
                case "getBoundSql" -> bound;
                case "query" -> { executed.set(true); yield java.util.List.of(1L); }
                default -> throw new AssertionError(m.getName());
            });
        var invocation=new org.apache.ibatis.plugin.Invocation(handler,org.apache.ibatis.executor.statement.StatementHandler.class
            .getMethod("query",java.sql.Statement.class,org.apache.ibatis.session.ResultHandler.class),new Object[]{null,null});
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending=worker.submit(()->{
                try {return ((org.apache.ibatis.plugin.Interceptor)fixture).intercept(invocation);}
                catch(Throwable error){throw new IllegalStateException(error);}
            });
            try {
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while(!fixtureType.getMethod("status").invoke(fixture).equals("ready")&&System.nanoTime()<until) Thread.sleep(5);
                assertEquals("ready",fixtureType.getMethod("status").invoke(fixture));
                assertTrue(executed.get(),"the actual count body must finish before the observable barrier");
                assertFalse(pending.isDone());
            } finally { fixtureType.getMethod("release").invoke(fixture); }
            assertEquals(java.util.List.of(1L),pending.get(3,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
