package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The built-in functions daysBetween, max and min. The legal source is asserted clean; each counterexample replaces
 * one named fragment of it and must be refused by exactly the rule under test.
 */
class BuiltInFunctionSemanticsTest {
    private static final String FIXTURE = "valid/built-in-functions.sir";
    private static final String FINE = "fine: max(0, daysBetween(loan.dueAt, now())) * 0.50;";
    private static final String CAPPED = "return min(daysBetween(input.since, input.until), input.cap);";

    @Test
    void theLegalSourceAnalyzesClean() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void daysBetweenNeedsTwoValuesOfOneTimeType() {
        assertOnly(replace(FINE, "fine: max(0, daysBetween(loan.dueAt, loan.startDay)) * 0.50;"), "SIR-TYPE-001");
    }

    @Test
    void daysBetweenDoesNotAcceptAnOptionalValue() {
        assertOnly(replace(FINE, "fine: max(0, daysBetween(loan.dueAt, loan.returnedAt)) * 0.50;"), "SIR-TYPE-001");
    }

    @Test
    void maxDoesNotChooseBetweenTexts() {
        assertOnly(replace(CAPPED, "return min(daysBetween(input.since, input.until), \"x\");"), "SIR-TYPE-001");
    }

    @Test
    void anIntegerLiteralTakesTheWidthOfTheOtherArgument() {
        // daysBetween is a 64-bit integer, so the literal 0 in max(0, ...) is read as one too.
        SemanticAnalysis result = TestSources.analyze(replace(CAPPED, "return max(0, daysBetween(input.since, input.until));"));

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void aFunctionOfTheQueriedRowCannotBeTranslatedIntoAQuery() {
        String source = replace(CAPPED, "find Loan where daysBetween(item.dueAt, input.until) > 3 order by id ascending as rows;\n"
                + "        return min(daysBetween(input.since, input.until), input.cap);");

        assertTrue(TestSources.errorCodes(TestSources.analyze(source)).contains("SIR-VALID-001"));
    }

    @Test
    void withoutTheLockTheGuardedUpdateStillCannotReadItsRow() {
        assertOnly(replace("load Loan by input.id as loan for update else LoanNotFound;", "load Loan by input.id as loan else LoanNotFound;"),
                "SIR-VALID-007");
    }

    private static void assertOnly(String source, String code) {
        SemanticAnalysis result = TestSources.analyze(source);
        assertEquals(List.of(code), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "fixture fragment missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
