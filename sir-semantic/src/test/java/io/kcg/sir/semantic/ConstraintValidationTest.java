package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import org.junit.jupiter.api.Test;

class ConstraintValidationTest {

    @Test
    void notBlankOnInt32ProducesTypeError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.NOTBLANK_ON_INT32);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void minOnStringProducesTypeError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.MIN_ON_STRING);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void duplicateNotBlankProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_NOTBLANK);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void minMaxConflictProducesValidationError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.MIN_MAX_CONFLICT);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
    }

    @Test
    void lengthWithWrongArgCountProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.LENGTH_WRONG_ARGS);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
    }

    @Test
    void unknownConstraintNameProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.UNKNOWN_CONSTRAINT);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
    }
}
