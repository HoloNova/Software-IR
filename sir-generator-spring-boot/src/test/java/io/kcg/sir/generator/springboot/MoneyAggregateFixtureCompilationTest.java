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
        var calendar=output.resolve("CalendarSnapshotFixture.java");
        Files.writeString(calendar,Files.readString(resources.resolve("conformance/mysql/CalendarSnapshotFixture.java.txt")));
        try (var loader = GeneratedRuntimeSupport.compile(source, output, List.of(file,grouped,calendar))) {
            assertNotNull(loader.loadClass("example.money.fixture.GroupedSumFixture"));
            verifyCalendarBarrier(loader);
            verifyRealAutoConfiguration(loader, false);
            verifyRealAutoConfiguration(loader, true);
            assertNotNull(loader.loadClass("example.money.application.GroupMeasuredService"));
            assertNotNull(loader.loadClass("example.money.fixture.ExactAggregateWarningFixture"));
            assertNotNull(loader.loadClass("example.money.application.SumMeasuredService"));
            assertNotNull(loader.loadClass("example.money.application.TaggedSumService"));
            assertNotNull(loader.loadClass("example.money.application.LiteralSumService"));
        }
        assertFalse(GeneratorTestSupport.generateSuccessFromText(source).stream().anyMatch(f -> f.relativePath().contains("WarningFixture") || f.relativePath().contains("GroupedSumFixture")),
                "the fixed SQL warning probe is not a generator output");
    }

    private static void verifyRealAutoConfiguration(java.net.URLClassLoader loader, boolean configured) throws Exception {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader);
            var properties = context.getEnvironment().getPropertySources();
            properties.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            properties.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            properties.addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment",
                    configured ? java.util.Map.of("KCG_ACTOR_IDENTITY_MODE", "external") : java.util.Map.of()));
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            context.registerBean(javax.sql.DataSource.class, () -> (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    loader, new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("toString")) return "no-connection fixture data source";
                        throw new AssertionError("container wiring must not access a database: " + method.getName());
                    }));
            // Suppress only the unrelated static global-context hook, so this test cannot contaminate later tests.
            context.registerBean(com.baomidou.mybatisplus.extension.spring.MybatisPlusApplicationContextAware.class,
                    definition -> definition.setLazyInit(true));
            context.register(com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class);
            context.register(loader.loadClass("example.money.Application$ActorIdentityRuntime"),
                    loader.loadClass("example.money.application.CredentialIdentityAdapter"),
                    loader.loadClass("example.money.persistence.UuidTypeHandler"),
                    loader.loadClass("example.money.api.ValidationSupport$UtcInstantHandler"),
                    loader.loadClass("example.money.fixture.CalendarSnapshotFixture"),loader.loadClass("example.money.persistence.ExactAggregate"),
                    loader.loadClass("example.money.fixture.GroupedSumFixture"),
                    loader.loadClass("example.money.fixture.ExactAggregateWarningFixture"));
            registerMapper(context, loader, "SummaryEntryMapper", "summaryEntryMapper");
            registerMapper(context, loader, "MeasurementMapper", "measurementMapper");
            registerMapper(context, loader, "AccessTokenMapper", "accessTokenMapper");
            registerMapper(context, loader, "RefreshTokenMapper", "refreshTokenMapper");

            if (!configured) {
                var failure = assertThrows(IllegalStateException.class, context::refresh);
                assertTrue(failure.getMessage().contains("KCG-ACTOR-STARTUP-001"), failure.toString());
                assertTrue(failure.getMessage().contains("kcg.actor-identity.mode"), failure.toString());
                assertFalse(context.isActive());
                return;
            }
            assertDoesNotThrow(context::refresh, "the real MyBatis auto-configuration must finish without circular dependencies");
            var factory = context.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
            assertEquals(loader.loadClass("example.money.api.ValidationSupport$UtcInstantHandler"),
                    factory.getConfiguration().getTypeHandlerRegistry().getTypeHandler(java.time.Instant.class).getClass(),
                    "the actual auto-configuration must register UTC temporal binding, not merely compile its class");
            var actorType = loader.loadClass("example.money.Application$ActorIdentityAdapter");
            var actors = context.getBeansOfType(actorType);
            assertEquals(java.util.Set.of(io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan.EXTERNAL_ADAPTER_BEAN_NAME), actors.keySet());
            assertEquals(loader.loadClass("example.money.application.CredentialIdentityAdapter"), actors.values().iterator().next().getClass());
            verifyAnonymousStillRefused(context, loader);
            Object fixture = context.getBean(loader.loadClass("example.money.fixture.GroupedSumFixture"));
            assertFalse(context.getDefaultListableBeanFactory().containsSingleton("summaryEntryMapper"),
                    "creating the interceptor must not resolve its lazy Mapper");
            assertTrue(factory.getConfiguration().getInterceptors().contains(fixture), "the barrier must remain installed");
            assertTrue(factory.getConfiguration().getInterceptors().contains(
                    context.getBean(loader.loadClass("example.money.persistence.ExactAggregate"))), "warning rejection must remain installed");
            verifyAfterCountBarrier(loader, fixture);
            var mapperType = loader.loadClass("example.money.persistence.SummaryEntryMapper");
            assertSame(context.getBean(mapperType), context.getBeanProvider(mapperType).getObject(),
                    "the actual Mapper must be resolvable after the SQL factory is complete");
        }
    }

    private static void verifyAnonymousStillRefused(org.springframework.context.ApplicationContext context,
            ClassLoader loader) throws Exception {
        var runtimeType = loader.loadClass("example.money.Application$ActorIdentityRuntime");
        var interceptor = (org.springframework.web.servlet.HandlerInterceptor) loader
                .loadClass("example.money.Application$ActorIdentityInterceptor")
                .getConstructor(runtimeType).newInstance(context.getBean(runtimeType));
        var request = (jakarta.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getAttribute" -> org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
                            .equals(arguments[0]) ? "/api/entries/monthly-totals" : null;
                    case "getHeader", "removeAttribute" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        var status = new java.util.concurrent.atomic.AtomicInteger();
        var response = (jakarta.servlet.http.HttpServletResponse) java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{jakarta.servlet.http.HttpServletResponse.class}, (proxy, method, arguments) -> {
                    assertEquals("setStatus", method.getName());
                    status.set((Integer) arguments[0]);
                    return null;
                });
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, status.get(), "external is real token authentication, not a fixed actor bypass");
    }

    private static void verifyCalendarBarrier(java.net.URLClassLoader loader) throws Exception {
        var type=loader.loadClass("example.money.fixture.CalendarSnapshotFixture");
        var fixture=(org.apache.ibatis.plugin.Interceptor)type.getConstructor().newInstance();type.getMethod("arm").invoke(fixture);
        var user=loader.loadClass("example.money.domain.User").getConstructor().newInstance();
        var executed=new java.util.concurrent.atomic.AtomicBoolean();
        var bound=new org.apache.ibatis.mapping.BoundSql(new org.apache.ibatis.session.Configuration(),"SELECT id,time_zone FROM user WHERE id=?",java.util.List.of(),null);
        var handler=(org.apache.ibatis.executor.statement.StatementHandler)java.lang.reflect.Proxy.newProxyInstance(loader,
            new Class<?>[]{org.apache.ibatis.executor.statement.StatementHandler.class},(p,m,a)->switch(m.getName()) {
                case "getBoundSql" -> bound;case "query" -> {executed.set(true);yield java.util.List.of(user);}
                default -> throw new AssertionError(m.getName());
            });
        var invocation=new org.apache.ibatis.plugin.Invocation(handler,org.apache.ibatis.executor.statement.StatementHandler.class
            .getMethod("query",java.sql.Statement.class,org.apache.ibatis.session.ResultHandler.class),new Object[]{null,null});
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var pending=worker.submit(()->{
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
                org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
                org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                try {return fixture.intercept(invocation);}catch(Throwable failure){throw new IllegalStateException(failure);}
                finally {org.springframework.transaction.support.TransactionSynchronizationManager.clear();}
            });
            try {
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while(!type.getMethod("status").invoke(fixture).equals("ready")&&System.nanoTime()<until)Thread.sleep(5);
                assertEquals("ready",type.getMethod("status").invoke(fixture));assertTrue(executed.get());assertFalse(pending.isDone());
            } finally {type.getMethod("release").invoke(fixture);}
            assertEquals(java.util.List.of(user),pending.get(3,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static void registerMapper(org.springframework.context.support.GenericApplicationContext context,
            ClassLoader loader, String simpleName, String beanName) throws ClassNotFoundException {
        var mapperType = loader.loadClass("example.money.persistence." + simpleName);
        var definition = new org.springframework.beans.factory.support.RootBeanDefinition(org.mybatis.spring.mapper.MapperFactoryBean.class);
        definition.getConstructorArgumentValues().addGenericArgumentValue(mapperType);
        definition.getPropertyValues().add("sqlSessionFactory", new org.springframework.beans.factory.config.RuntimeBeanReference("sqlSessionFactory"));
        definition.setAttribute(org.springframework.beans.factory.FactoryBean.OBJECT_TYPE_ATTRIBUTE, mapperType);
        definition.setLazyInit(true);
        context.registerBeanDefinition(beanName, definition);
    }

    private static void verifyAfterCountBarrier(java.net.URLClassLoader loader, Object fixture) throws Exception {
        var fixtureType=fixture.getClass();
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
