package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstShiftDirection;
import io.kcg.sir.ast.AstTimeUnit;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.type.PrimitiveType;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of plus, minus and time shifts. The legal source is asserted clean and checked for the
 * shape the target needs; each counterexample replaces one named fragment of it and must be refused
 * by exactly the rule under test, so an edit that fails to apply fails the test instead of silently
 * analyzing the legal source again.
 */
class ArithmeticSemanticsTest {
    private static final String FIXTURE = "valid/time-arithmetic.sir";

    @Test
    void theLegalSourceAnalyzesCleanAndTheShiftsKeepTheirTypes() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());

        NormalizedStep.CreateStep create = (NormalizedStep.CreateStep) steps(result, "OpenTicket").stream()
                .filter(NormalizedStep.CreateStep.class::isInstance)
                .findFirst()
                .orElseThrow();
        NormalizedExpression.TimeShiftExpression expires = shift(create, "expiresAt");
        assertEquals(PrimitiveType.DATE_TIME, expires.type());
        assertEquals(AstShiftDirection.ADD, expires.direction());
        assertEquals(BigInteger.valueOf(15), expires.amount());
        assertEquals(AstTimeUnit.MINUTES, expires.unit());
        assertEquals(PrimitiveType.DATE, shift(create, "dueOn").type());

        NormalizedExpression.BinaryExpression attempts = assertInstanceOf(
                NormalizedExpression.BinaryExpression.class, binding(create, "attempts"));
        assertEquals(AstBinaryOperator.ADD, attempts.operator());
        assertEquals(PrimitiveType.INT32, attempts.type());
    }

    @Test
    void aDateOnlyMovesByWholeDays() {
        assertOnly("SIR-TYPE-001", replace("dueOn: input.start + 3 days;", "dueOn: input.start + 3 hours;"));
    }

    @Test
    void onlyADateOrADateTimeCanBeShifted() {
        SemanticAnalysis result = TestSources.analyze(replace("attempts: input.base + 1;", "attempts: input.base + 1 hours;"));

        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"), () -> result.diagnostics().toString());
    }

    @Test
    void bothSidesOfPlusAndMinusAreTheSameNumericType() {
        assertOnly("SIR-TYPE-001", replace("score: input.bonus - input.bonus;", "score: input.bonus - input.base;"));
    }

    @Test
    void textIsNotNumeric() {
        assertOnly("SIR-TYPE-001", replace("validate input.base - 1 + 2 > 0 else Expired;",
                "validate input.start - input.start == input.start else Expired;"));
    }

    @Test
    void aShiftAmountIsBoundedByAThirtyTwoBitInteger() {
        assertOnly("SIR-TYPE-001", replace("expiresAt: now() + 15 minutes;", "expiresAt: now() + 2147483648 minutes;"));
    }

    @Test
    void arithmeticOnAColumnCannotBeCarriedIntoAFindQuery() {
        assertOnly("SIR-VALID-001", replace("where item.expiresAt > now() + 5 minutes", "where item.expiresAt + 5 minutes > now()"));
        assertOnly("SIR-VALID-001", replace("where item.expiresAt > now() + 5 minutes", "where item.attempts + 1 > 2"));
    }

    @Test
    void aValueComputedFromTheClockOrTheRequestIsFineInAFindQuery() {
        SemanticAnalysis result = TestSources.analyze(replace("where item.expiresAt > now() + 5 minutes",
                "where item.expiresAt > now() + 5 minutes and item.attempts > input.page - 1"));

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    private static List<NormalizedStep> steps(SemanticAnalysis result, String capabilityName) {
        return result.model().orElseThrow().declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .filter(value -> value.name().equals(capabilityName))
                .findFirst()
                .orElseThrow()
                .workflow()
                .steps();
    }

    private static NormalizedExpression binding(NormalizedStep.CreateStep create, String fieldName) {
        return create.bindings().stream()
                .filter(value -> value.fieldName().equals(fieldName))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static NormalizedExpression.TimeShiftExpression shift(NormalizedStep.CreateStep create, String fieldName) {
        return assertInstanceOf(NormalizedExpression.TimeShiftExpression.class, binding(create, fieldName));
    }

    private static void assertOnly(String code, String source) {
        SemanticAnalysis result = TestSources.analyze(source);

        assertEquals(List.of(code), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "the fragment to replace is missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
