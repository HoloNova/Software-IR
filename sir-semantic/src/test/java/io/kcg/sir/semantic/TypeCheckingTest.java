package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import org.junit.jupiter.api.Test;

class TypeCheckingTest {

    @Test
    void optionalOfOptionalIsIllegal() {
        SemanticAnalysis result = TestSources.analyze(TestSources.OPTIONAL_OF_OPTIONAL);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-003"));
    }

    @Test
    void refToNonEntityProducesTypeError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.REF_TO_ENUM);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-002"));
    }

    @Test
    void refToUndefinedProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.REF_TO_UNDEFINED);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"));
    }

    @Test
    void entityFieldWithoutRefProducesTypeError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.ENTITY_FIELD_WITHOUT_REF);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void optionalUnitIsIllegal() {
        SemanticAnalysis result = TestSources.analyze(TestSources.OPTIONAL_UNIT);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void unitAsFieldTypeIsIllegal() {
        SemanticAnalysis result = TestSources.analyze(TestSources.UNIT_AS_FIELD);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void int32NotAssignableToInt64() {
        SemanticAnalysis result = TestSources.analyze(TestSources.INT32_TO_INT64);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void returnTypeMismatchProducesTypeError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.RETURN_TYPE_MISMATCH);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }

    @Test
    void validateNonBooleanConditionProducesTypeError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.VALIDATE_NON_BOOLEAN);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"));
    }
}
