package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import org.junit.jupiter.api.Test;

class NormalizationTest {

    @Test
    void failsNotSortedProducesValidationError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.FAILS_NOT_SORTED);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
    }

    @Test
    void requiresNotSortedProducesValidationError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.REQUIRES_NOT_SORTED);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-VALID-001"));
    }

    @Test
    void failsDuplicateProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.FAILS_DUPLICATE);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void requiresDuplicateProducesSymbolError() {
        SemanticAnalysis result = TestSources.analyze(TestSources.REQUIRES_DUPLICATE);
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-001"));
    }

    @Test
    void normalizedModelContainsCanonicalFailsOrder() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());
        NormalizedCapability cap = findCapability(result);
        assertEquals(1, cap.fails().size());
        assertEquals("sir://CampusMarket/error/InvalidGoodsPrice", cap.fails().get(0).value());
    }

    @Test
    void normalizedModelContainsCanonicalRequiresOrder() {
        SemanticAnalysis result = TestSources.analyzeResource(TestSources.VALID_CAMPUS_MARKET);
        assertTrue(result.isSuccess());
        NormalizedCapability cap = findCapability(result);
        assertEquals(2, cap.requires().size());
        assertEquals(AstRequirementKind.AUTHENTICATED, cap.requires().get(0));
        assertEquals(AstRequirementKind.ATOMIC, cap.requires().get(1));
    }

    private NormalizedCapability findCapability(SemanticAnalysis result) {
        return result.model().orElseThrow().declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .findFirst()
                .orElseThrow();
    }
}