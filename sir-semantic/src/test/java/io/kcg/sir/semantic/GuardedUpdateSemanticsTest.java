package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of the guard on an update. The legal source is asserted clean and checked for the shape the
 * target needs; each counterexample replaces one named fragment of it and must be refused by exactly the
 * rule under test, so an edit that fails to apply fails the test instead of silently analyzing the legal
 * source again.
 */
class GuardedUpdateSemanticsTest {
    private static final String FIXTURE = "valid/guarded-update.sir";
    private static final String GUARDED = "update old when old.used == false and old.expiresAt > now() else Invalid { used: true; }";

    @Test
    void theLegalSourceAnalyzesCleanAndTheGuardSurvivesNormalization() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());

        NormalizedStep.UpdateStep update = result.model().orElseThrow().declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(NormalizedStep.UpdateStep.class::isInstance)
                .map(NormalizedStep.UpdateStep.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(update.guard().isPresent());
        assertTrue(update.guardError().isPresent());
    }

    @Test
    void theGuardMustBeBoolean() {
        SemanticAnalysis result = TestSources.analyze(replace(GUARDED, "update old when old.owner else Invalid { used: true; }"));

        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"), () -> result.diagnostics().toString());
    }

    @Test
    void aBareBooleanColumnIsNotAComparison() {
        assertOnly("SIR-VALID-007", replace(GUARDED, "update old when old.used else Invalid { used: true; }"), "SIR-VALID-007");
    }

    @Test
    void theGuardsErrorMustBeDeclaredByTheCapability() {
        assertOnly("SIR-FLOW-003", replace("fails Invalid;\n", ""), "SIR-FLOW-003");
    }

    @Test
    void aGuardIsNotAllowedOnARowThisWorkflowCreated() {
        assertOnly("SIR-VALID-007", replace("persist old;\n        persist fresh;",
                "persist old;\n        update fresh when fresh.used == false else Invalid { used: true; }\n        persist fresh;"), "SIR-VALID-007");
    }

    @Test
    void aGuardIsNotAllowedOnAVersionedEntity() {
        SemanticAnalysis result = TestSources.analyze(replace("field expiresAt: Moment;\n    }\n    view",
                "field expiresAt: Moment;\n      field version: Version;\n    }\n    view"));

        assertTrue(TestSources.hasError(result, "SIR-VALID-007"), () -> result.diagnostics().toString());
    }

    @Test
    void theColumnMustBeOnTheLeftAndTheValueMustNotReadTheRow() {
        assertOnly("SIR-VALID-007", replace(GUARDED, "update old when false == old.used else Invalid { used: true; }"), "SIR-VALID-007");
        assertOnly("SIR-VALID-007", replace(GUARDED, "update old when old.owner == old.owner else Invalid { used: true; }"), "SIR-VALID-007");
    }

    @Test
    void onlyComparisonsCombinedByAndOrNotAreGuards() {
        assertOnly("SIR-VALID-007", replace(GUARDED, "update old when true else Invalid { used: true; }"), "SIR-VALID-007");
    }

    @Test
    void aBindingCannotReadAnotherColumnOfTheRowItWrites() {
        assertOnly("SIR-VALID-007", replace("{ used: true; }", "{ owner: old.owner; }").replace("old.used == false and old.expiresAt > now()", "old.used == false"),
                "SIR-VALID-007");
    }

    @Test
    void aBindingMayReadOtherVariablesAndTheRequest() {
        SemanticAnalysis result = TestSources.analyze(replace("{ used: true; }", "{ owner: input.token; }"));

        assertTrue(result.isSuccess() || !TestSources.hasError(result, "SIR-VALID-007"), () -> result.diagnostics().toString());
    }

    private static void assertOnly(String code, String source, String ruleThatMustBeReported) {
        SemanticAnalysis result = TestSources.analyze(source);

        assertTrue(TestSources.hasError(result, ruleThatMustBeReported), () -> result.diagnostics().toString());
        assertEquals(List.of(code), TestSources.errorCodes(result).stream().distinct().toList(), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "the fragment to replace is missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
