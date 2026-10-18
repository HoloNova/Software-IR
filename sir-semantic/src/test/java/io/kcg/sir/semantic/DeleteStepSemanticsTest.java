package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code delete row;} removes a row a load read, and nothing else. */
class DeleteStepSemanticsTest {
    private static final String BASE = """
            entity Note persistent {
              identity id: Id generated auto;
              field text: Text;
            }
            input NoteRef {
              field id: Id;
            }
            error NoteMissing http 404;
            """;

    private static String capability(String exposure, String requires, String steps) {
        return BASE + "capability Run {\n  input NoteRef;\n  output Unit;\n  fails NoteMissing;\n  " + requires + "\n  expose " + exposure
            + ";\n  workflow {\n" + steps + "\n    return unit;\n  }\n}\n";
    }

    private static final String LOAD = "    load Note by input.id as row else NoteMissing;\n";

    @Test
    void aLoadedRowCanBeDeletedAndTheModelHasTheStep() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(capability("command", "requires atomic;", LOAD + "    delete row;")));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var capability = result.model().orElseThrow().declarations().stream().filter(NormalizedCapability.class::isInstance)
            .map(NormalizedCapability.class::cast).findFirst().orElseThrow();
        assertTrue(capability.workflow().steps().stream().anyMatch(NormalizedStep.DeleteStep.class::isInstance));
    }

    @Test
    void deleteIsRefusedWhereItDoesNotMakeSense() {
        Map<String, String> refusals = Map.of(
            "SIR-VALID-005 created", capability("command", "requires atomic;", "    create Note as made { text: \"x\"; }\n    delete made;"),
            "SIR-VALID-005 twice", capability("command", "requires atomic;", LOAD + "    delete row;\n    delete row;"),
            "SIR-VALID-005 update after", capability("command", "requires atomic;", LOAD + "    delete row;\n    update row { text: \"y\"; }"),
            "SIR-VALID-005 persist after", capability("command", "requires atomic;", LOAD + "    delete row;\n    persist row;"),
            "SIR-FLOW-004 readonly", capability("query", "requires readonly;", LOAD + "    delete row;"));
        refusals.forEach((label, source) -> {
            String code = label.split(" ")[0];
            SemanticAnalysis result = TestSources.analyze(TestSources.sir(source));
            assertFalse(result.isSuccess(), label);
            assertTrue(TestSources.hasError(result, code), label + " -> " + result.diagnostics());
        });
    }

    @Test
    void deleteIsAContextualWordSoItStaysALegalName() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity delete persistent {\n  identity id: Id generated auto;\n  field delete: Text;\n}\n"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void anUnknownVariableIsRefusedLikeAnyOtherStep() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(capability("command", "requires atomic;", LOAD + "    delete missing;")));
        assertFalse(result.isSuccess());
        assertTrue(List.of("SIR-SYMBOL-002", "SIR-SYMBOL-003", "SIR-FLOW-001", "SIR-VALID-005").stream().anyMatch(code -> TestSources.hasError(result, code)), result.diagnostics().toString());
    }
}
