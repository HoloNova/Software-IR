package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated-code contract of plus, minus and time shifts: what keeps an overflow from wrapping
 * around, and which Java type each shift moves.
 */
class GeneratorArithmeticContractTest {
    private static final String SOURCE = "valid/time-arithmetic.sir";
    private static final String APPLICATION = "src/main/java/example/tickets/application/";

    private final List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(SOURCE);

    @Test
    void aDateTimeMovesAsAnInstantAndADateAsALocalDate() {
        String service = content(APPLICATION + "OpenTicketService.java");

        assertTrue(service.contains("ticket.setExpiresAt((java.time.Instant.now()).plus(15L, java.time.temporal.ChronoUnit.MINUTES));"), service);
        assertTrue(service.contains("ticket.setDueOn((input.getStart()).plus(3L, java.time.temporal.ChronoUnit.DAYS));"), service);
    }

    @Test
    void shiftsChainAndASubtractedShiftUsesMinus() {
        String service = content(APPLICATION + "OpenTicketService.java");

        assertTrue(service.contains("(((java.time.Instant.now()).plus(1L, java.time.temporal.ChronoUnit.DAYS))"
                + ".minus(2L, java.time.temporal.ChronoUnit.HOURS))"), service);
    }

    @Test
    void integersUseTheExactFormsSoOverflowThrowsInsteadOfWrappingAndDecimalsUseBigDecimal() {
        String service = content(APPLICATION + "OpenTicketService.java");

        assertTrue(service.contains("ticket.setAttempts(Math.addExact(input.getBase(), 1));"), service);
        assertTrue(service.contains("Math.subtractExact(input.getBase(), Math.subtractExact(input.getBase(), 1))"), service);
        assertTrue(service.contains("ticket.setScore((input.getBonus()).subtract(input.getBonus()));"), service);
        assertFalse(service.contains("input.getBase() + 1"), service);
        assertFalse(service.contains("input.getBase() - 1"), service);
    }

    @Test
    void aValueComputedFromTheClockIsBoundIntoTheQueryNotTranslatedIntoIt() {
        String service = content(APPLICATION + "LiveTicketsService.java");

        assertTrue(service.contains("q0.gt(Ticket::getExpiresAt, (java.time.Instant.now()).plus(5L, java.time.temporal.ChronoUnit.MINUTES))"), service);
    }

    private String content(String path) {
        return files.stream()
                .filter(file -> file.relativePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing generated file " + path))
                .content();
    }
}
