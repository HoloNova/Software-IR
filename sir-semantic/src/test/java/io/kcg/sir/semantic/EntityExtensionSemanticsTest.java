package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEntityExtension;
import io.kcg.sir.semantic.model.NormalizedField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code extend Entity { fields }} adds fields to an entity; the model shows them where they are written. */
class EntityExtensionSemanticsTest {
    private static final String ENTITY = "entity Account persistent {\n  identity id: Id generated auto;\n  field name: Name;\n}\n";

    @Test
    void theEntityIsImplementedWithTheAddedFieldsButTheModelKeepsThemInTheExtension() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(ENTITY + "extend Account {\n  field visits: Count default 0;\n}\n"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var model = result.model().orElseThrow();
        assertEquals(List.of("name"), fields(model.declarations()));
        assertEquals(List.of("visits"), model.declarations().stream().filter(NormalizedEntityExtension.class::isInstance)
            .map(NormalizedEntityExtension.class::cast).findFirst().orElseThrow().fields().stream().map(NormalizedField::name).toList());
        assertEquals(List.of("name", "visits"), fields(model.implementable().declarations()));
        assertTrue(model.implementable().declarations().stream().noneMatch(NormalizedEntityExtension.class::isInstance));
    }

    @Test
    void aFeatureBringsTheFieldsItNeedsIntoTheAuthorsEntity() {
        String feature = "feature Visits {\n  parameter owner: name;\n  template ```\nextend ${owner} {\n  field visits: Count default 0;\n}\n```;\n}\n"
            + "use Visits {\n  owner: Account;\n}\n";
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(ENTITY + feature));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var model = result.model().orElseThrow();
        var extension = model.declarations().stream().filter(NormalizedEntityExtension.class::isInstance).findFirst().orElseThrow();
        assertFalse(model.isWritten(extension), "the extension is the feature's, not the author's");
        assertEquals(List.of("name", "visits"), fields(model.implementable().declarations()));
    }

    @Test
    void anExtensionIsRefusedWhereItCannotAddAField() {
        Map<String, String> refusals = Map.of(
            "SIR-EXTEND-001 not an entity", ENTITY + "extend Missing {\n  field visits: Count;\n}\n",
            "SIR-SYMBOL-001 same name", ENTITY + "extend Account {\n  field name: Name;\n}\n");
        refusals.forEach((label, source) -> {
            SemanticAnalysis result = TestSources.analyze(TestSources.sir(source));
            assertFalse(result.isSuccess(), label);
            assertTrue(TestSources.hasError(result, label.split(" ")[0]), label + " -> " + result.diagnostics());
        });
    }

    @Test
    void extendIsAContextualWordSoItStaysALegalName() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity extend persistent {\n  identity id: Id generated auto;\n  field extend: Text;\n}\n"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    private static List<String> fields(List<?> declarations) {
        return declarations.stream().filter(NormalizedEntity.class::isInstance).map(NormalizedEntity.class::cast).findFirst().orElseThrow()
            .fields().stream().map(NormalizedField::name).toList();
    }
}
