package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code default <constant>} lets a create step leave a field unbound, and nothing else. */
class FieldDefaultSemanticsTest {
    private static String software(String field, String binding) {
        return "entity Ticket persistent {\n  identity id: Id generated auto;\n  field title: Text;\n  " + field + "\n}\n"
            + "input Draft {\n  field title: Text;\n}\n"
            + "capability Open {\n  input Draft;\n  output Unit;\n  requires atomic;\n  expose command;\n  workflow {\n"
            + "    create Ticket as made { title: input.title;" + binding + " }\n    persist made;\n    return unit;\n  }\n}\n";
    }

    @Test
    void aFieldWithADefaultNeedsNoBinding() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(software("field state: Text default \"open\";", "")));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void aFieldWithoutOneStillNeedsIt() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(software("field state: Text;", "")));
        assertFalse(result.isSuccess());
        assertTrue(TestSources.hasError(result, "SIR-FLOW-001"), result.diagnostics().toString());
    }

    @Test
    void aBindingStillOverridesTheDefault() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir(software("field state: Text default \"open\";", " state: \"done\";")));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void aDefaultMustFitTheFieldAndTheKindOfField() {
        Map<String, String> refusals = Map.of(
            "SIR-TYPE-001 wrong type", software("field state: Text default 3;", ""),
            "SIR-VALID-010 unique", software("field state: Text unique default \"a\";", ""),
            "SIR-VALID-010 secret", software("field state: Text secret default \"a\";", ""));
        refusals.forEach((label, source) -> {
            SemanticAnalysis result = TestSources.analyze(TestSources.sir(source));
            assertFalse(result.isSuccess(), label);
            assertTrue(TestSources.hasError(result, label.split(" ")[0]), label + " -> " + result.diagnostics());
        });
    }

    @Test
    void defaultIsAContextualWordSoItStaysALegalName() {
        SemanticAnalysis result = TestSources.analyze(TestSources.sir("entity default persistent {\n  identity id: Id generated auto;\n  field default: Text;\n}\n"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }
}
