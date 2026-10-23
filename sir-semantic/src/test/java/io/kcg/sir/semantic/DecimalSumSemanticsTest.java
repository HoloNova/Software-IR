package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecimalSumSemanticsTest {
    @Test void decimalAndOptionalMoneyBindAContextualSumResult() {
        var result = TestSources.analyze(source());
        assertTrue(result.isSuccess(), result.diagnostics().toString());
    }
    @ParameterizedTest
    @ValueSource(strings = {"Id", "Count", "Text", "Flag", "Ref<Owner>"})
    void nonDecimalMeasurementsAreRejected(String type) {
        var result = TestSources.analyze(source().replace("Optional<Money>", type));
        assertFalse(result.isSuccess(), type);
        assertTrue(TestSources.errorCodes(result).contains("SIR-TYPE-001"), result.diagnostics().toString());
    }
    @Test void sourceMustExistAndMeasurementMustBelongToIt() {
        assertFalse(TestSources.analyze(source().replace("sum Entry.amount", "sum Unknown.amount")).isSuccess());
        assertFalse(TestSources.analyze(source().replace("sum Entry.amount", "sum Entry.missing")).isSuccess());
    }
    @ParameterizedTest
    @ValueSource(strings = {"requires atomic; expose command", "requires readonly; expose command", "expose query"})
    void sumRequiresAnExplicitReadonlyQuery(String mode) {
        var result=TestSources.analyze(source().replace("requires readonly; expose query",mode));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.errorCodes(result).contains("SIR-FLOW-004"),result.diagnostics().toString());
    }
    @Test void itemCannotEscapeTheSumAndResultCannotBeUsedBeforeItIsBound() {
        assertFalse(TestSources.analyze(source().replace("return total;", "return item.amount;")).isSuccess());
        assertFalse(TestSources.analyze(source().replace("item.owner == input.owner", "total > 0.0")).isSuccess());
    }
    @Test void predicateIsBooleanAndResultIsNotCount() {
        assertFalse(TestSources.analyze(source().replace("sum Entry.amount where item.owner == input.owner", "sum Entry.amount where item.amount")).isSuccess());
        assertFalse(TestSources.analyze(source().replace("return total;", "return total and true;")).isSuccess());
    }
    static String source() { return TestSources.resource("valid/decimal-sum.sir"); }
}
