package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import org.junit.jupiter.api.Test;

class WorkflowValidationTest {

    @Test
    void validateWithUndeclaredErrorProducesSymbolError() {
        // ADR-005: undeclared error references are caught by ResolvePass (SIR-SYMBOL-002).
        // ValidatePass skips unbound sites to avoid duplicate "undefined" diagnostics.
        SemanticAnalysis result = TestSources.analyze(TestSources.VALIDATE_UNDECLARED_ERROR);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
    }

    @Test
    void loadWithUndeclaredErrorProducesSymbolError() {
        // ADR-005: undeclared error references are caught by ResolvePass (SIR-SYMBOL-002).
        // ValidatePass skips unbound sites to avoid duplicate "undefined" diagnostics.
        SemanticAnalysis result = TestSources.analyze(TestSources.LOAD_UNDECLARED_ERROR);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
    }

    @Test
    void missingReturnProducesFlowError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.MISSING_RETURN);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-002"));
    }

    @Test
    void undefinedVariableProducesFlowError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.UNDEFINED_VARIABLE);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-001"));
    }

    @Test
    void createMissingRequiredFieldProducesFlowError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.CREATE_MISSING_REQUIRED);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-001"));
    }

    @Test
    void queryWithPersistProducesFlowError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.QUERY_WITH_PERSIST);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-004"));
    }

    @Test
    void queryWithoutReadonlyProducesValidationError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.QUERY_WITHOUT_READONLY);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
    }

    @Test
    void atomicPlusReadonlyProducesValidationError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.ATOMIC_PLUS_READONLY);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
    }

    @Test
    void multiWriteWithoutAtomicProducesValidationError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.MULTI_WRITE_WITHOUT_ATOMIC);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
    }
}
