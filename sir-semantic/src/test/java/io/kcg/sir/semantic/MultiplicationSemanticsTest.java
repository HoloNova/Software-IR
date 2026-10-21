package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.source.SirDeclarationWriter;
import io.kcg.sir.semantic.type.PrimitiveType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MultiplicationSemanticsTest {
    private static String source(String expression) {
        return TestSources.sir("""
                input Numbers {
                  field count: Count; field wide: Id; field price: Money;
                  field optional: Optional<Count>; field label: Text; field flag: Flag; field day: Day;
                }
                error Refused;
                capability Calculate {
                  input Numbers; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate %s == %s else Refused; return unit; }
                }
                """.formatted(expression, expression));
    }

    @ParameterizedTest
    @CsvSource({"input.count * input.count,INT32", "input.wide * input.wide,INT64",
            "input.wide * 2,INT64", "2 * input.wide,INT64", "input.price * input.price,DECIMAL",
            "input.price * input.count,DECIMAL", "input.count * input.price,DECIMAL",
            "input.price * input.wide,DECIMAL", "input.wide * input.price,DECIMAL"})
    void exactResultTypesDoNotRetypeTheInputFields(String expression, String expected) {
        var result = TestSources.analyze(source(expression));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertEquals(PrimitiveType.valueOf(expected), value(result).type());
    }

    @ParameterizedTest
    @ValueSource(strings = {"input.count * input.wide", "input.optional * input.count",
            "input.label * input.count", "input.flag * input.count", "input.day * input.count",
            "input.price * 2147483648", "input.price + input.count", "input.price - input.wide"})
    void invalidOperandsAndOldMixedAddSubtractRulesAreNotWidened(String expression) {
        var result = TestSources.analyze(source(expression));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"), result.diagnostics().toString());
    }

    @Test
    void precedenceAndRightGroupingSurviveSourceWriting() {
        String expression = "input.count + input.count * (input.count + 1) * (input.count * 2)";
        var result = TestSources.analyze(source(expression));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var addition = assertInstanceOf(NormalizedExpression.BinaryExpression.class, value(result));
        assertEquals("ADD", addition.operator().name());
        var multiplication = assertInstanceOf(NormalizedExpression.BinaryExpression.class, addition.right());
        assertEquals("MUL", multiplication.operator().name());
        assertEquals("MUL", assertInstanceOf(NormalizedExpression.BinaryExpression.class, multiplication.left()).operator().name());
        var declarations = SoftwareDraft.of(result.model().orElseThrow()).declarations();
        var writer = new SirDeclarationWriter("", SirDeclarationWriter.typesOf(declarations));
        String written = declarations.stream().map(writer::write).reduce("", (a,b) -> a + "\n" + b);
        assertTrue(written.contains(expression), written);
        assertTrue(TestSources.analyze(TestSources.sir(written)).isSuccess(), written);
    }

    @Test
    void unaryNegationBindsMoreTightlyThanMultiplication() {
        var result = TestSources.analyze(source("-input.count * input.count"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var multiplication = assertInstanceOf(NormalizedExpression.BinaryExpression.class, value(result));
        assertInstanceOf(NormalizedExpression.UnaryExpression.class, multiplication.left());
    }

    @Test
    void queryColumnMultiplicationIsStillRejectedButRequestArithmeticIsAllowed() {
        String original = TestSources.resource("valid/time-arithmetic.sir");
        for (String predicate : List.of("item.attempts * 2 > input.page", "item.attempts > input.page * 2")) {
            var result = TestSources.analyze(original.replace("item.expiresAt > now() + 5 minutes", predicate));
            if (predicate.startsWith("item.attempts *")) {
                assertTrue(TestSources.hasError(result, "SIR-VALID-001"), result.diagnostics().toString());
            } else {
                assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
            }
        }
    }

    @Test
    void multiplyingTheTargetColumnIsNotAnAtomicDelta() {
        var result = TestSources.analyze(TestSources.sir("""
                entity Wallet persistent { identity id: Id generated auto; field balance: Money; }
                input Amount { field id: Id; field amount: Money; }
                error Missing; error Refused;
                capability Change {
                  input Amount; output Unit; fails Missing; fails Refused; requires atomic; expose command;
                  workflow {
                    load Wallet by input.id as wallet else Missing;
                    update wallet when wallet.balance >= 0.0 else Refused { balance: wallet.balance * input.amount; }
                    persist wallet;
                    return unit;
                  }
                }
                """));
        assertTrue(TestSources.hasError(result, "SIR-VALID-007"), result.diagnostics().toString());
    }

    private static NormalizedExpression value(io.kcg.sir.semantic.api.SemanticAnalysis result) {
        var capability = result.model().orElseThrow().declarations().stream().filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast).findFirst().orElseThrow();
        var validation = (NormalizedStep.ValidateStep) capability.workflow().steps().getFirst();
        return ((NormalizedExpression.BinaryExpression) validation.condition()).left();
    }
}
