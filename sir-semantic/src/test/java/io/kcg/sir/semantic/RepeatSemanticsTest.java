package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The counted loop {@code repeat n times limit N else E as k} and the function {@code addDays}. The legal source books
 * the same hours on consecutive weeks; each counterexample replaces one named fragment and must be refused by exactly
 * the rule under test.
 */
class RepeatSemanticsTest {
    private static final String FIXTURE = "valid/weekly-booking.sir";
    private static final String HEAD = "repeat input.weeks times limit 12 else TooManyWeeks as week {";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void theCountIsAnInt32() {
        assertOnly(replace(HEAD, "repeat input.roomId times limit 12 else TooManyWeeks as week {"), "SIR-TYPE-001");
    }

    @Test
    void theBoundIsOneToAHundred() {
        assertOnly(replace(HEAD, "repeat input.weeks times limit 101 else TooManyWeeks as week {"), "SIR-VALID-001");
    }

    @Test
    void theFailureMustBeDeclared() {
        String source = replace("      fails TooManyWeeks;\n", "");
        assertTrue(TestSources.errorCodes(TestSources.analyze(source)).contains("SIR-FLOW-003"), source);
    }

    @Test
    void thePassNumberIsNotVisibleAfterTheLoop() {
        assertOnly(replace("        return room;", "        validate week > 0 else SlotTaken;\n        return room;"), "SIR-FLOW-001");
    }

    @Test
    void anOuterRowIsOnlyAccumulatedInto() {
        assertOnly(replace("booked: room.booked + 1;", "booked: week;"), "SIR-FLOW-004");
    }

    @Test
    void aLoopInsideARepeatIsRefused() {
        assertOnly(replace("          persist made;\n", "          persist made;\n"
                + "          repeat 2 times limit 2 else TooManyWeeks as again {\n            validate again >= 0 else SlotTaken;\n          }\n"),
                "SIR-FLOW-004");
    }

    @Test
    void addDaysMovesAPointInTimeByAnInteger() {
        assertOnly(replace("startsAt: addDays(input.startsAt, week * 7);", "startsAt: addDays(week, 7);"), "SIR-TYPE-001");
    }

    private static void assertOnly(String source, String code) {
        SemanticAnalysis result = TestSources.analyze(source);
        assertEquals(List.of(code), TestSources.errorCodes(result).stream().distinct().toList(), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "fixture fragment missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
