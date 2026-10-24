package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual composed app guard, credential adapter, pool/Mapper wiring and CI-only session filter, with no DB access. */
class ShopIdentityCompilationTest {
    static URLClassLoader loader;
    @BeforeAll static void compile(@TempDir Path root) throws Exception {
        var resources=Path.of(System.getProperty("basedir",".")).resolveSibling("sir-toolchain-application").resolve("src/test/resources");
        var fixture=root.resolve("ShopSessionFixture.java");Files.copy(resources.resolve("conformance/mysql/ShopSessionFixture.java.txt"),fixture);
        loader=GeneratedRuntimeSupport.compile(Files.readString(resources.resolve("valid/shop-conformance.sir")),root.resolve("compiled"),List.of(fixture));
    }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void theActualContainerRejectsMissingModeAndWiresExternalWithoutAnyConnection(boolean external) throws Exception {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader);context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            context.getEnvironment().getPropertySources().remove("systemProperties");context.getEnvironment().getPropertySources().remove("systemEnvironment");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment",external?Map.of("KCG_ACTOR_IDENTITY_MODE","external"):Map.of()));
            context.registerBean(javax.sql.DataSource.class,()->(javax.sql.DataSource)Proxy.newProxyInstance(loader,new Class<?>[]{javax.sql.DataSource.class},(p,m,a)->{
                if(m.getName().equals("toString"))return "no-connection shop fixture";throw new AssertionError("must not access DB: "+m.getName());}));
            context.registerBean(com.baomidou.mybatisplus.extension.spring.MybatisPlusApplicationContextAware.class,d->d.setLazyInit(true));
            context.register(com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class,
                org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration.class);
            context.register(type("Application$ActorIdentityRuntime"),type("application.CredentialIdentityAdapter"),type("persistence.UuidTypeHandler"),type("fixture.ShopSessionFixture"));
            for(String name:List.of("AccessToken","RefreshToken")) {
                var mapper=type("persistence."+name+"Mapper");var definition=new org.springframework.beans.factory.support.RootBeanDefinition(org.mybatis.spring.mapper.MapperFactoryBean.class);
                definition.getConstructorArgumentValues().addGenericArgumentValue(mapper);definition.getPropertyValues().add("sqlSessionFactory",new org.springframework.beans.factory.config.RuntimeBeanReference("sqlSessionFactory"));
                definition.setAttribute(org.springframework.beans.factory.FactoryBean.OBJECT_TYPE_ATTRIBUTE,mapper);definition.setLazyInit(true);
                context.registerBeanDefinition(Character.toLowerCase(name.charAt(0))+name.substring(1)+"Mapper",definition);
            }
            if(!external) {
                var error=assertThrows(IllegalStateException.class,context::refresh);assertTrue(error.getMessage().contains("KCG-ACTOR-STARTUP-001"),error.toString());return;
            }
            assertDoesNotThrow(context::refresh);
            assertNotNull(context.getBean(org.apache.ibatis.session.SqlSessionFactory.class));
            assertNotNull(context.getBean(type("fixture.ShopSessionFixture")));
            assertEquals(Set.of(io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan.EXTERNAL_ADAPTER_BEAN_NAME),context.getBeansOfType(type("Application$ActorIdentityAdapter")).keySet());
            var runtime=type("Application$ActorIdentityRuntime");var interceptor=(org.springframework.web.servlet.HandlerInterceptor)type("Application$ActorIdentityInterceptor").getConstructor(runtime).newInstance(context.getBean(runtime));
            var request=(jakarta.servlet.http.HttpServletRequest)Proxy.newProxyInstance(loader,new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->switch(m.getName()){
                case "getMethod"->"POST";case "getAttribute"->org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE.equals(a[0])?"/api/orders/{id}/cancel":null;
                case "getHeader","removeAttribute"->null;default->throw new AssertionError(m.getName());});
            var status=new java.util.concurrent.atomic.AtomicInteger();
            var response=(jakarta.servlet.http.HttpServletResponse)Proxy.newProxyInstance(loader,new Class<?>[]{jakarta.servlet.http.HttpServletResponse.class},(p,m,a)->{assertEquals("setStatus",m.getName());status.set((Integer)a[0]);return null;});
            assertFalse(interceptor.preHandle(request,response,new Object()));assertEquals(401,status.get());
        }
    }
    static Class<?> type(String name) throws Exception {return loader.loadClass("example.multiplication."+name);}
}
