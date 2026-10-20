package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScalarQuerySemanticsTest {
    @Test
    void explicitQueriesBindBooleanAndLongResultsAndRelatedRequestValues() {
        var result = TestSources.analyzeResource("valid/scalar-queries.sir");
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"found < 5", "total and found", "total == input.owner"})
    void resultTypesAreNotInterchangeable(String condition) {
        var result = TestSources.analyze(source().replace("found or total == 0", condition));
        assertTrue(TestSources.errorCodes(result).contains("SIR-TYPE-001"), () -> result.diagnostics().toString());
    }

    @Test
    void predicateRequiresBooleanAndDoesNotExposeItemOutsideQuery() {
        var badPredicate = TestSources.analyze(source().replace("item.owner == input.owner and (input.minimum is none or item.score >= input.minimum)", "item.score"));
        assertTrue(TestSources.errorCodes(badPredicate).contains("SIR-TYPE-001"));
        var leaked = TestSources.analyze(source().replace("found or total == 0", "item.removed == false"));
        assertTrue(TestSources.errorCodes(leaked).contains("SIR-FLOW-001"));
    }

    @Test
    void queriesCannotReferToTheirResultBeforeItIsDeclared() {
        var result = TestSources.analyze(source().replace("item.owner == input.owner and (input.minimum is none or item.score >= input.minimum)", "found"));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.errorCodes(result).contains("SIR-FLOW-001"));
    }

    @Test
    void countsCompareWith64BitLiteralsAndQueryWordsRemainLegalVariableNames() {
        var large = TestSources.analyze(source().replace("found or total == 0", "total == 2147483648"));
        assertTrue(large.isSuccess(), () -> large.diagnostics().toString());
        var contextual = TestSources.analyze(source().replace("as found;", "as exists;").replace("validate found or", "validate exists or"));
        assertTrue(contextual.isSuccess(), () -> contextual.diagnostics().toString());
    }

    @Test
    void unknownQuerySourcesRemainStructuredFailures() {
        var result = TestSources.analyze(source().replace("exists Entry where", "exists Missing where"));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.errorCodes(result).contains("SIR-SYMBOL-002"), () -> result.diagnostics().toString());
    }

    static String source() { return TestSources.resource("valid/scalar-queries.sir"); }
}
