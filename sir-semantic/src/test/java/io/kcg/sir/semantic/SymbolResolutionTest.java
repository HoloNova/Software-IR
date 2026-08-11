package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import org.junit.jupiter.api.Test;

class SymbolResolutionTest {

    @Test
    void duplicateEntityNameProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_ENTITY);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void duplicateEnumMemberProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_ENUM_MEMBER);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void duplicateFieldNameProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_FIELD);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void undefinedTypeReferenceProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.UNDEFINED_TYPE);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
    }

    @Test
    void primitiveShadowingProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.PRIMITIVE_SHADOWING);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void duplicateErrorProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_ERROR);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void duplicateCapabilityProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.DUPLICATE_CAPABILITY);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }
}