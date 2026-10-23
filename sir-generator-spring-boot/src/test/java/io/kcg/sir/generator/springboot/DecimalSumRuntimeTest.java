package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecimalSumRuntimeTest {
    @TempDir Path work;
    @Test void sumsDoNotModifyEntityMapperArtifactsAndWarningSupportIsConditional() {
        String source = GeneratorTestSupport.resourceText("valid/decimal-sum.sir");
        var with = GeneratorTestSupport.generateSuccessFromText(source);
        var without = GeneratorTestSupport.generateSuccessFromText(source.replace("sum Entry.amount where", "exists Entry where")
                .replace("return total;", "return 0.0;").replace("return sum;", "return 0.0;"));
        var a = with.stream().filter(f -> f.relativePath().endsWith("Mapper.java")).toList();
        var b = without.stream().filter(f -> f.relativePath().endsWith("Mapper.java")).toList();
        assertEquals(a.stream().map(f -> f.relativePath() + "\n" + f.content()).toList(), b.stream().map(f -> f.relativePath() + "\n" + f.content()).toList());
        assertEquals(1, with.stream().filter(f -> f.relativePath().endsWith("/ExactAggregate.java")).count());
        assertFalse(without.stream().anyMatch(f -> f.relativePath().endsWith("/ExactAggregate.java")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"q0", "q1"})
    void sumResultNamesCannotCollideWithPredicateLambdaParameters(String name) throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/decimal-sum.sir")
                .replace("as total;", "as "+name+";").replace("return total;", "return "+name+";");
        try(var ignored=GeneratedRuntimeSupport.compile(source,work)){assertNotNull(ignored);}
    }

    @Test void generatedQueryTemporariesCannotCollideWithLegalWorkflowVariables() throws Exception {
        String source = GeneratorTestSupport.resourceText("valid/decimal-sum.sir")
                .replace("sum Entry.amount", "count Entry where true as totalAggregateQuery;\n        sum Entry.amount");
        try (var ignored = GeneratedRuntimeSupport.compile(source, work)) {
            assertNotNull(ignored.loadClass("example.sum.application.TotalService"));
        }
    }

    @Test void emittedServiceUsesOneMarkedAggregateAndKeepsExactResultsBeyondSingleMoneyPrecision() throws Exception {
        try (var loader = GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/decimal-sum.sir"), work)) {
            var mapper = loader.loadClass("example.sum.persistence.EntryMapper");
            var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
            var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "sum-runtime");
            assistant.setCurrentNamespace(mapper.getName());
            new com.baomidou.mybatisplus.core.injector.DefaultSqlInjector().inspectInject(assistant, mapper);
            var returned = new AtomicReference<List<?>>(List.of(new BigDecimal("199999999999999999.9800")));
            var calls = new java.util.ArrayList<String>();
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{mapper}, (p, method, args) -> {
                assertEquals("selectObjs", method.getName());
                var query = (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) args[0];
                assertEquals("example.sum.persistence.ExactAggregate$Query", query.getClass().getName());
                var mapped = configuration.getMappedStatement(mapper.getName() + ".selectObjs");
                var parameters = java.util.Map.of("ew", query);
                var sql = mapped.getBoundSql(parameters);
                String text = sql.getSql(); calls.add(text);
                assertTrue(text.contains("COALESCE(SUM(`amount`), 0)"), text);
                assertTrue(text.contains("removed") && text.contains("owner_id"), text);
                var values = new java.util.ArrayList<Object>();
                var jdbc = (java.sql.PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.PreparedStatement.class}, (a, m, v) -> {
                            values.add(m.getName().equals("setNull") ? null : v[1]); return null;
                        });
                new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(mapped, parameters, sql).setParameters(jdbc);
                assertTrue(values.contains(42L), values.toString());
                assertTrue(values.stream().noneMatch(v -> v instanceof Optional<?>), values.toString());
                return returned.get();
            });
            var inputClass = loader.loadClass("example.sum.api.Filter");
            var input = inputClass.getConstructor().newInstance();
            inputClass.getMethod("setOwnerId", Long.class).invoke(input, 42L);
            var serviceClass = loader.loadClass("example.sum.application.TotalService");
            var service = serviceClass.getConstructor(mapper).newInstance(proxy);
            var total = serviceClass.getMethod("total", inputClass);
            assertEquals(new BigDecimal("199999999999999999.9800"), total.invoke(service, input));
            returned.set(List.of(BigDecimal.ZERO));
            assertEquals(BigDecimal.ZERO, total.invoke(service, input));
            assertEquals(2, calls.size());
            returned.set(List.of(1.5d));
            var wrong = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> total.invoke(service, input));
            assertInstanceOf(IllegalStateException.class, wrong.getCause());
            returned.set(List.of());
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> total.invoke(service, input));
        }
    }
}
