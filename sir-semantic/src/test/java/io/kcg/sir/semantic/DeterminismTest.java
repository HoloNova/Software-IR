package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DeterminismTest {

    @Test
    void sameInputProducesSameResultTwice() {
        String source = TestSources.resource(TestSources.VALID_CAMPUS_MARKET);
        SemanticAnalysis first = TestSources.analyze(source);
        SemanticAnalysis second = TestSources.analyze(source);

        assertEquals(first.isSuccess(), second.isSuccess());
        assertEquals(
                TestSources.errorCodes(first),
                TestSources.errorCodes(second));
    }

    @Test
    void localeSwitchDoesNotChangeResult() {
        String source = TestSources.resource(TestSources.VALID_CAMPUS_MARKET);

        Locale original = Locale.getDefault();
        SemanticAnalysis before = TestSources.analyze(source);
        try {
            Locale.setDefault(Locale.GERMANY);
            SemanticAnalysis after = TestSources.analyze(source);
            assertEquals(
                    TestSources.errorCodes(before),
                    TestSources.errorCodes(after));
            assertEquals(before.isSuccess(), after.isSuccess());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void normalizedModelDeclarationsAreUnmodifiable() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());
        assertThrows(UnsupportedOperationException.class,
                () -> result.model().orElseThrow().declarations().add(null));
    }

    @Test
    void normalizedModelExpressionTypesAreUnmodifiable() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());
        assertThrows(UnsupportedOperationException.class,
                () -> result.model().orElseThrow().expressionTypes().put(null, null));
    }

    @Test
    void symbolTableAllSymbolsAreUnmodifiable() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());
        assertThrows(UnsupportedOperationException.class,
                () -> result.model().orElseThrow().symbols().all().add(null));
    }
}