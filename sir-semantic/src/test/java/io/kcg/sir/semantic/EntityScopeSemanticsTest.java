package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code scope <condition>;} hides an entity's other rows from every load, find and any(), and is written once. */
class EntityScopeSemanticsTest {
    private static final String ENTITY = "entity Note persistent {\n  identity id: Id generated auto;\n  field text: Text;\n"
        + "  field removed: Flag default false;\n  scope %s;\n}\n";
    private static final String REST = "input NoteRef {\n  field id: Id;\n}\nerror NoteMissing http 404;\n"
        + "capability Show {\n  input NoteRef;\n  output Unit;\n  fails NoteMissing;\n  requires readonly;\n  expose query;\n"
        + "  workflow {\n    load Note by input.id as row else NoteMissing;\n    return unit;\n  }\n}\n";

    private static SemanticAnalysis analyze(String scope) {
        return TestSources.analyze(TestSources.sir(ENTITY.formatted(scope) + REST));
    }

    @Test
    void aLoadOfAScopedEntityChecksTheScopeOnlyInWhatIsImplemented() {
        SemanticAnalysis result = analyze("removed == false");
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var model = result.model().orElseThrow();
        assertEquals(List.of("LoadStep", "ReturnStep"), stepKinds(model.declarations()));
        assertEquals(List.of("LoadStep", "ValidateStep", "ReturnStep"), stepKinds(model.implementable().declarations()));
    }

    @Test
    void aScopeMustBeAConditionOnTheEntitysOwnRow() {
        Map<String, String> refusals = Map.of(
            "SIR-TYPE-001 not boolean", "text",
            "SIR-FLOW-001 unknown name", "gone == false",
            "SIR-VALID-011 any", "any(Note, removed == false)");
        refusals.forEach((label, scope) -> {
            SemanticAnalysis result = analyze(scope);
            assertFalse(result.isSuccess(), label);
            assertTrue(TestSources.hasError(result, label.split(" ")[0]), label + " -> " + result.diagnostics());
        });
    }

    @Test
    void scopeIsAContextualWordSoItStaysALegalName() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity scope persistent {\n  identity id: Id generated auto;\n  field scope: Text;\n}\n"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    private static List<String> stepKinds(List<?> declarations) {
        return declarations.stream().filter(NormalizedCapability.class::isInstance).map(NormalizedCapability.class::cast).findFirst().orElseThrow()
            .workflow().steps().stream().map(step -> ((NormalizedStep) step).getClass().getSimpleName()).toList();
    }
}
