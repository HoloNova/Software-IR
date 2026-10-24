package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import org.apache.ibatis.type.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarUtcJdbcRuntimeTest {
    @TempDir static Path work;
    static URLClassLoader loader;
    @BeforeAll static void compile() throws Exception { loader=GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/calendar-month.sir"),work); }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked")
    static TypeHandler<Instant> handler() throws Exception {
        return (TypeHandler<Instant>)loader.loadClass("example.calendar.api.ValidationSupport$UtcInstantHandler").getConstructor().newInstance();
    }
    @ParameterizedTest @ValueSource(strings={"1000-01-01T00:00:00Z","1500-02-01T00:00:00Z","1582-10-01T00:00:00Z","2026-03-01T05:00:00.123456Z","9999-12-01T00:00:00Z"})
    void theRealHandlerBindsProlepticUtcFieldsAndReadsThemBack(String text) throws Exception {
        var instant=Instant.parse(text);var utc=LocalDateTime.ofInstant(instant,ZoneOffset.UTC);
        var writes=new java.util.concurrent.atomic.AtomicInteger();
        var prepared=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            assertEquals("setObject",m.getName(),"Timestamp's hybrid calendar and default timezone must not participate");
            assertEquals(1,a[0]);assertEquals(utc,a[1]);assertEquals(Types.TIMESTAMP,a[2]);writes.incrementAndGet();return null;
        });
        var handler=handler();handler.setParameter(prepared,1,instant,JdbcType.TIMESTAMP);assertEquals(1,writes.get());
        var result=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{
            assertEquals("getObject",m.getName());assertEquals(LocalDateTime.class,a[1]);return utc;
        });
        assertEquals(instant,handler.getResult(result,1));assertEquals(instant,handler.getResult(result,"occurred_at"));
    }
    @Test void sqlNullRemainsNullAndTheHandlerIsDiscoverable() throws Exception {
        var handler=handler();
        var result=(ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->null);
        assertNull(handler.getResult(result,1));
        var call=(CallableStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CallableStatement.class},(p,m,a)->null);
        assertNull(handler.getResult(call,1));
        assertNotNull(handler.getClass().getAnnotation(org.springframework.stereotype.Component.class));
        assertArrayEquals(new Class<?>[]{Instant.class},handler.getClass().getAnnotation(MappedTypes.class).value());
    }
}
