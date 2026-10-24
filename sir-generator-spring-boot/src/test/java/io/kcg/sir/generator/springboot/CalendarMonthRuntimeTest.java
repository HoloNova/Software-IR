package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CalendarMonthRuntimeTest {
    private URLClassLoader loader;
    @BeforeAll void compile(@TempDir Path work) throws Exception { loader=GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/calendar-month.sir"),work); }
    @AfterAll void close() throws Exception { if(loader!=null)loader.close(); }
    @ParameterizedTest
    @CsvSource({
        "2000-02,UTC,2000-02-01T00:00:00Z,2000-03-01T00:00:00Z",
        "1900-02,UTC,1900-02-01T00:00:00Z,1900-03-01T00:00:00Z",
        "2026-12,UTC,2026-12-01T00:00:00Z,2027-01-01T00:00:00Z",
        "2026-01,Asia/Shanghai,2025-12-31T16:00:00Z,2026-01-31T16:00:00Z",
        "2026-03,America/New_York,2026-03-01T05:00:00Z,2026-04-01T04:00:00Z",
        "2026-11,America/New_York,2026-11-01T04:00:00Z,2026-12-01T05:00:00Z",
        "2015-11,America/Havana,2015-11-01T04:00:00Z,2015-12-01T05:00:00Z",
        "2017-10,America/Asuncion,2017-10-01T04:00:00Z,2017-11-01T03:00:00Z",
        "2026-10,Australia/Lord_Howe,2026-09-30T13:30:00Z,2026-10-31T13:00:00Z",
        "2011-12,Pacific/Apia,2011-12-01T10:00:00Z,2011-12-31T10:00:00Z"
    })
    void actualServicesComputeBothCalendarBoundaries(String month,String zone,String start,String end) throws Exception {
        assertEquals(Instant.parse(start),invoke("MonthStart",month,zone));
        assertEquals(Instant.parse(end),invoke("MonthEnd",month,zone));
    }
    @ParameterizedTest
    @CsvSource({"NULL,UTC","2026-01,NULL","2026-1,UTC","2026-00,UTC","2026-13,UTC","2026-01,Mars/Base",
        "2026-01,CST","2026-01,+08:00","2026-01,UTC+08:00","0000-01,UTC","0999-12,UTC","9999-12,UTC","1000-01,Asia/Shanghai"})
    void invalidMonthZoneOrTargetBoundaryIsADeclared400(String month,String zone) throws Exception {
        var thrown=assertThrows(InvocationTargetException.class,()->invoke("MonthStart",month.equals("NULL")?null:month,zone.equals("NULL")?null:zone));
        assertEquals("BadPeriodException",thrown.getCause().getClass().getSimpleName());
    }
    @Test void literalCalendarDoesNotInventAnInputParameter(@TempDir Path directory) throws Exception {
        String source=GeneratorTestSupport.resourceText("valid/calendar-month.sir").replace("input CalendarInput;","")
            .replace("month input.month in input.zone","month \"2026-01\" in \"UTC\"");
        try(var literal=GeneratedRuntimeSupport.compile(source,directory)) {
            var service=literal.loadClass("example.calendar.application.MonthStartService");
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"),service.getMethod("monthStart").invoke(service.getConstructor().newInstance()));
        }
    }
    @Test void anAbsentPayloadIsADeclaredFailureNotAnNpe() throws Exception {
        var service=loader.loadClass("example.calendar.application.MonthStartService");
        var error=assertThrows(InvocationTargetException.class,()->service.getMethod("monthStart",loader.loadClass("example.calendar.api.CalendarInput"))
            .invoke(service.getConstructor().newInstance(),new Object[]{null}));
        assertEquals("BadPeriodException",error.getCause().getClass().getSimpleName());
    }
    @Test void calendarReadOnlyServiceHasAnExplicitSnapshot() throws Exception {
        var annotation=loader.loadClass("example.calendar.application.MonthStartService")
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(annotation);assertTrue(annotation.readOnly());
        assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,annotation.isolation());
    }
    private Object invoke(String capability,String month,String zone) throws Exception {
        var type=loader.loadClass("example.calendar.api.CalendarInput");var input=type.getConstructor().newInstance();
        type.getMethod("setMonth",Optional.class).invoke(input,Optional.ofNullable(month));
        type.getMethod("setZone",Optional.class).invoke(input,Optional.ofNullable(zone));
        var service=loader.loadClass("example.calendar.application."+capability+"Service");
        var instance=service.getConstructor().newInstance();
        var method=capability.substring(0,1).toLowerCase()+capability.substring(1);
        return service.getMethod(method,type).invoke(instance,input);
    }
}
