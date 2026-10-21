package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedExpression.IntegerLiteral;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.source.SirDeclarationWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyPrecisionSemanticsTest {
    private static String entity(String field) {
        return "entity Wallet persistent { identity id: Id generated auto; " + field + " }";
    }

    @Test
    void moneyBringsPrecisionAndExplicitTypeAndFieldRulesReplaceIt() {
        var result = TestSources.analyze(TestSources.sir("""
                type UnitPrice = Money where precision(19, 4), min(0);
                entity Wallet persistent {
                  identity id: Id generated auto;
                  field balance: Money;
                  field price: UnitPrice;
                  field tiny: UnitPrice where precision(2, 2);
                  field optional: Optional<Money>;
                }
                """));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var wallet = (NormalizedEntity) result.model().orElseThrow().declarations().stream()
                .filter(NormalizedEntity.class::isInstance).findFirst().orElseThrow();
        assertEquals(List.of("19,2", "19,4", "2,2", "19,2"), wallet.fields().stream().map(field -> {
            var precision = field.constraints().stream().filter(c -> c.name().equals("precision")).findFirst().orElseThrow();
            return precision.arguments().stream().map(a -> ((IntegerLiteral) a).value().toString()).reduce((a,b) -> a + "," + b).orElseThrow();
        }).toList());
        var declarations = SoftwareDraft.of(result.model().orElseThrow()).declarations();
        var writer = new SirDeclarationWriter("", SirDeclarationWriter.typesOf(declarations));
        String written = declarations.stream().map(writer::write).reduce("", (a,b) -> a + "\n" + b);
        assertTrue(written.contains("field balance: Money;"), written);
        assertTrue(written.contains("field price: UnitPrice;"), written);
        assertTrue(written.contains("field tiny: UnitPrice where precision(2, 2);"), written);
        assertTrue(TestSources.analyze(TestSources.sir(written)).isSuccess(), written);
    }

    @Test
    void patchUsesEntityRulesRatherThanDefaultsBroughtByItsPayloadType() {
        var result = TestSources.analyze(TestSources.sir("""
                entity Wallet persistent {
                  identity id: Id generated auto;
                  field balance: Money where precision(21, 4);
                  field rebate: Optional<Money>;
                  field version: Version;
                }
                input EditWallet patch of Wallet {
                  field id: Id;
                  field balance: Money;
                  field rebate: Optional<Money>;
                }
                """));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var input = result.model().orElseThrow().declarations().stream()
                .filter(io.kcg.sir.semantic.model.NormalizedInput.class::isInstance)
                .map(io.kcg.sir.semantic.model.NormalizedInput.class::cast).findFirst().orElseThrow();
        assertTrue(input.fields().stream().allMatch(field -> field.constraints().isEmpty()));
    }

    @Test
    void patchStillCannotOverrideTheEntityPrecisionExplicitly() {
        var result = TestSources.analyze(TestSources.sir("""
                entity Wallet persistent {
                  identity id: Id generated auto;
                  field balance: Money;
                  field version: Version;
                }
                input EditWallet patch of Wallet {
                  field id: Id;
                  field balance: Money where precision(21, 4);
                }
                """));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-003"), result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"precision(0, 0)", "precision(-1, 0)", "precision(2, -1)", "precision(2, 3)",
            "precision(2.0, 1)", "precision(2, 1.0)", "precision(2147483648, 1)", "precision(2)",
            "precision(2, 1, 0)", "precision(2, 1), precision(3, 1)"})
    void invalidPrecisionIsRejectedOnTypesAndFields(String rule) {
        for (String declaration : List.of("type Price = Money where " + rule + ";",
                entity("field balance: Money where " + rule + ";"))) {
            var result = TestSources.analyze(TestSources.sir(declaration));
            assertFalse(result.isSuccess(), declaration);
            assertTrue(result.diagnostics().stream().allMatch(d -> d.primarySpan() != null), result.diagnostics().toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Text", "Count", "Flag", "Optional<Count>"})
    void precisionIsOnlyForDecimalValues(String type) {
        var result = TestSources.analyze(TestSources.sir(entity("field balance: " + type + " where precision(19, 2);")));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"), result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.234", "100000000000000000.0", "-100000000000000000.0"})
    void defaultsMustBeExactlyRepresentable(String value) {
        var result = TestSources.analyze(TestSources.sir(entity("field balance: Money default " + value + ";")));
        assertFalse(result.isSuccess(), value);
        assertTrue(TestSources.hasError(result, "SIR-VALID-010"), result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "1.2300", "-1.23", "99999999999999999.99"})
    void exactDefaultsIncludingTrailingZerosAndNegativeAmountsRemainValid(String value) {
        var result = TestSources.analyze(TestSources.sir(entity("field balance: Money default " + value + ";")));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }
}
