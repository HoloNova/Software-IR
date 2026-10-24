package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generated Java for daysBetween, max and min, run against real values; no database is started. */
class BuiltInFunctionRuntimeTest {
    @TempDir Path work;

    @Test void daysBetweenCountsWholeDaysRoundedDownAndMayBeNegative() throws Exception {
        try (var loader = compile()) {
            Instant since = Instant.parse("2026-01-01T00:00:00Z");
            assertEquals(3L, capped(loader, since, since.plus(Duration.ofHours(3 * 24 + 12)), 10L), "3.5 days are 3 whole days");
            assertEquals(2L, capped(loader, since, since.plus(Duration.ofDays(5)), 2L), "min keeps the smaller value");
            assertEquals(-1L, capped(loader, since, since.minus(Duration.ofHours(1)), 10L), "an hour earlier rounds down to -1");
            assertEquals(0L, capped(loader, since, since.plus(Duration.ofHours(23)), 10L));
        }
    }

    @Test void aLateReturnIsFinedPerWholeDayAndAnEarlyOneIsNot() throws Exception {
        try (var loader = compile()) {
            Object late = giveBack(loader, Instant.now().minus(Duration.ofDays(3)).minus(Duration.ofHours(1)));
            assertEquals(0, new BigDecimal("1.50").compareTo((BigDecimal) get(late, "fine")), "3 whole days at 0.50");
            Object early = giveBack(loader, Instant.now().plus(Duration.ofDays(2)));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) get(early, "fine")), "max(0, ...) never fines an early return");
            assertEquals(4L, get(late, "plannedDays"), "a view field counts calendar days between two dates");
            assertEquals(LocalDate.parse("2026-03-05"), get(late, "lastDay"), "max of two dates is the later one");
        }
    }

    private URLClassLoader compile() throws Exception {
        return GeneratedRuntimeSupport.compile(GeneratorTestSupport.resourceText("valid/built-in-functions.sir"), work);
    }

    private static Object capped(ClassLoader loader, Instant since, Instant until, long cap) throws Exception {
        var inputType = loader.loadClass("example.fines.api.Window");
        Object input = inputType.getConstructor().newInstance();
        inputType.getMethod("setSince", Instant.class).invoke(input, since);
        inputType.getMethod("setUntil", Instant.class).invoke(input, until);
        inputType.getMethod("setCap", Long.class).invoke(input, cap);
        var service = loader.loadClass("example.fines.application.CappedDaysService");
        Object instance = service.getConstructors()[0].newInstance();
        return service.getMethod("cappedDays", inputType).invoke(instance, input);
    }

    private static Object giveBack(ClassLoader loader, Instant dueAt) throws Exception {
        var loanType = loader.loadClass("example.fines.domain.Loan");
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "fines");
        assistant.setCurrentNamespace("fines.test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, loanType);
        Object loan = loanType.getConstructor().newInstance();
        loanType.getMethod("setId", Long.class).invoke(loan, 7L);
        loanType.getMethod("setDueAt", Instant.class).invoke(loan, dueAt);
        loanType.getMethod("setReturned", Boolean.class).invoke(loan, false);
        loanType.getMethod("setFine", BigDecimal.class).invoke(loan, new BigDecimal("0.00"));
        loanType.getMethod("setStartDay", LocalDate.class).invoke(loan, LocalDate.parse("2026-03-01"));
        loanType.getMethod("setEndDay", LocalDate.class).invoke(loan, LocalDate.parse("2026-03-05"));
        var mapperType = loader.loadClass("example.fines.persistence.LoanMapper");
        Object mapper = Proxy.newProxyInstance(loader, new Class<?>[]{mapperType}, (proxy, method, args) -> switch (method.getName()) {
            case "selectOne" -> loan;
            case "update" -> 1;
            default -> throw new AssertionError("unexpected mapper call " + method);
        });
        var service = loader.loadClass("example.fines.application.GiveBackService");
        Object instance = service.getConstructors()[0].newInstance(mapper);
        var inputType = loader.loadClass("example.fines.api.LoanRef");
        Object input = inputType.getConstructor().newInstance();
        inputType.getMethod("setId", Long.class).invoke(input, 7L);
        return service.getMethod("giveBack", inputType).invoke(instance, input);
    }

    private static Object get(Object view, String property) throws Exception {
        return view.getClass().getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1)).invoke(view);
    }
}
