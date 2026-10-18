package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstIntegerLiteral;
import io.kcg.sir.ast.AstNowExpression;
import io.kcg.sir.ast.AstShiftDirection;
import io.kcg.sir.ast.AstTimeShiftExpression;
import io.kcg.sir.ast.AstTimeUnit;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for plus, minus and time shifts: where they bind, how they chain, what
 * a time shift is made of, and that the unit words stay ordinary names everywhere else.
 */
class ArithmeticGrammarTest {
    private final SirParser parser = new DefaultSirParser();

    @Test
    void aTimeShiftIsAnExpressionMadeOfABaseAnAmountAndAUnit() {
        AstTimeShiftExpression shift = assertInstanceOf(AstTimeShiftExpression.class, condition("now() + 15 minutes"));

        assertInstanceOf(AstNowExpression.class, shift.base());
        assertEquals(AstShiftDirection.ADD, shift.direction());
        assertEquals(BigInteger.valueOf(15), shift.amount());
        assertEquals(AstTimeUnit.MINUTES, shift.unit());
    }

    @Test
    void everyUnitWordIsAcceptedInTheSingularAndThePlural() {
        for (String word : List.of("second", "seconds", "minute", "minutes", "hour", "hours", "day", "days")) {
            AstTimeShiftExpression shift = assertInstanceOf(AstTimeShiftExpression.class, condition("now() - 1 " + word));
            assertEquals(AstShiftDirection.SUBTRACT, shift.direction(), word);
            assertEquals(AstTimeUnit.valueOf(word.replaceAll("s$", "").toUpperCase() + "S"), shift.unit(), word);
        }
    }

    @Test
    void shiftsChainToTheLeft() {
        AstTimeShiftExpression outer = assertInstanceOf(AstTimeShiftExpression.class, condition("now() + 1 day - 2 hours"));

        assertEquals(AstShiftDirection.SUBTRACT, outer.direction());
        assertEquals(AstTimeUnit.HOURS, outer.unit());
        AstTimeShiftExpression inner = assertInstanceOf(AstTimeShiftExpression.class, outer.base());
        assertEquals(AstTimeUnit.DAYS, inner.unit());
    }

    @Test
    void plusAndMinusChainToTheLeftAndBindTighterThanAComparison() {
        AstBinaryExpression comparison = assertInstanceOf(AstBinaryExpression.class, condition("input.a - 1 + 2 > input.b"));

        assertEquals(AstBinaryOperator.GT, comparison.operator());
        AstBinaryExpression sum = assertInstanceOf(AstBinaryExpression.class, comparison.left());
        assertEquals(AstBinaryOperator.ADD, sum.operator());
        AstBinaryExpression difference = assertInstanceOf(AstBinaryExpression.class, sum.left());
        assertEquals(AstBinaryOperator.SUB, difference.operator());
        assertEquals(BigInteger.TWO, assertInstanceOf(AstIntegerLiteral.class, sum.right()).value());
    }

    @Test
    void aMinusAfterAnOperatorIsStillAPrefixNegation() {
        AstBinaryExpression equality = assertInstanceOf(AstBinaryExpression.class, condition("input.a - -1 == 0"));
        AstBinaryExpression difference = assertInstanceOf(AstBinaryExpression.class, equality.left());

        assertEquals(AstBinaryOperator.SUB, difference.operator());
        assertInstanceOf(AstUnaryExpression.class, difference.right());
    }

    @Test
    void anIntegerNotFollowedByAUnitWordIsPlainArithmetic() {
        AstBinaryExpression comparison = assertInstanceOf(AstBinaryExpression.class, condition("input.a + 5 > 0"));
        AstBinaryExpression sum = assertInstanceOf(AstBinaryExpression.class, comparison.left());

        assertEquals(AstBinaryOperator.ADD, sum.operator());
        assertInstanceOf(AstIntegerLiteral.class, sum.right());
    }

    @Test
    void theUnitWordsAreStillOrdinaryNamesWhereTheyAreNotAUnit() {
        SourceUnitParseResult result = parse("""
                entity Window persistent {
                  identity id: Id generated auto;
                  field days: Count;
                  field minutes: Count;
                }
                error NotFound;
                input In { field hours: Count; }
                capability Check {
                  input In;
                  output Unit;
                  fails NotFound;
                  requires readonly;
                  expose query;
                  workflow {
                    validate input.hours + 1 > 0 else NotFound;
                    return unit;
                  }
                }
                """);

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        AstExpression first = firstCondition(result);
        assertInstanceOf(AstBinaryExpression.class, first);
        assertEquals(AstBinaryOperator.GT, ((AstBinaryExpression) first).operator());
    }

    @Test
    void aMalformedShiftOrDanglingOperatorIsAGrammarError() {
        for (String bad : List.of(
                "now() + 5 weeks",
                "now() + minutes 5",
                "now() + 5 minutes minutes",
                "now() +",
                "now() + 5 min",
                "now() + 5 Minutes")) {
            SourceUnitParseResult result = parse(wrap(bad));
            assertFalse(result.isSuccess(), bad);
            assertTrue(
                    result.diagnostics().stream().map(Diagnostic::code).anyMatch(code -> code.value().startsWith("SIR-PARSE-")),
                    () -> bad + ": " + messages(result.diagnostics()));
        }
    }

    private AstExpression condition(String expression) {
        SourceUnitParseResult result = parse(wrap(expression));
        assertTrue(result.isSuccess(), () -> expression + ": " + messages(result.diagnostics()));
        return firstCondition(result);
    }

    private static AstExpression firstCondition(SourceUnitParseResult result) {
        AstCapabilityDecl capability = (AstCapabilityDecl) ParseTestSupport.document(result.unit().orElseThrow()).software().declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        return ((AstValidateStep) capability.workflow().steps().get(0)).condition();
    }

    private static String wrap(String expression) {
        return """
                error NotFound;
                input In { field a: Count; field b: Count; }
                capability Check {
                  input In;
                  output Unit;
                  fails NotFound;
                  requires readonly;
                  expose query;
                  workflow {
                    validate %s else NotFound;
                    return unit;
                  }
                }
                """.formatted(expression);
    }

    private SourceUnitParseResult parse(String declarations) {
        return parser.parseProject(new SirSource(SourceId.of("tests/arithmetic.sir"), """
                sir 0.3
                software Arithmetic {
                  metadata {
                    displayName "Arithmetic";
                    namespace "tests.grammar.arithmetic";
                  }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
                  sources { }
                  imports { }
                  declarations {
                    %s
                  }
                }
                """.formatted(declarations)));
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(value -> value.code().value() + ": " + value.message())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }
}
