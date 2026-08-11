package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import org.junit.jupiter.api.Test;

class LegalSemanticTest {

    @Test
    void campusMarketIsSemanticallyValid() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void forwardReferencesAreAllowed() {
        SemanticAnalysis result = TestSources.analyze(TestSources.FORWARD_REFERENCE);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void allWorkflowStepsAreValid() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_ALL_WORKFLOW);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void unitCapabilityIsValid() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_UNIT_CAPABILITY);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void tAssignableToOptionalT() {
        SemanticAnalysis result = TestSources.analyze(TestSources.T_TO_OPTIONAL_T);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void nowExpressionIsDateTime() {
        SemanticAnalysis result = TestSources.analyze(TestSources.NOW_IS_DATETIME);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void enumMemberAccessIsValid() {
        SemanticAnalysis result = TestSources.analyze(TestSources.ENUM_MEMBER_ACCESS);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }

    @Test
    void constraintOrderPreservedIsValid() {
        SemanticAnalysis result = TestSources.analyze(TestSources.CONSTRAINT_ORDER_PRESERVED);
        assertTrue(result.isSuccess(), () -> "Expected success but got errors: " + TestSources.errorCodes(result));
    }
}
