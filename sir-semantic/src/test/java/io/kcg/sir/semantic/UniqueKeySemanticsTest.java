package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code unique (a, b);} says no two rows hold the same values of all these fields together. The fields have to be
 * ones the database can hold in an index and compare, so the rest is refused where it is written.
 */
class UniqueKeySemanticsTest {
    private static SemanticAnalysis analyze(String entityBody) {
        return TestSources.analyze(TestSources.sir("""
            entity Student persistent {
              identity id: Id generated auto;
              field name: Name;
            }
            entity Pair persistent {
              identity id: Id generated auto;
              field student: Ref<Student>;
              field code: Text where length(1, 40);
              field note: Optional<Text> where length(0, 20);
              field loose: Text;
              field secretWord: Secret;
              field version: Version;
              field big: Text where length(1, 700);
              field big2: Text where length(1, 100);
            %s
            }
            """.formatted(entityBody)));
    }

    @Test void aKeepsItsFieldsInTheOrderTheyWereWritten() {
        SemanticAnalysis result = analyze("  unique (code, student);\n  unique (student, big);");
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        NormalizedEntity pair = (NormalizedEntity) result.model().orElseThrow().declarations().stream()
            .filter(d -> d.name().equals("Pair")).findFirst().orElseThrow();
        List<List<String>> keys = pair.uniqueKeys().stream().map(key -> key.fields().stream()
            .map(id -> result.model().orElseThrow().symbols().byId(id).orElseThrow().name()).toList()).toList();
        assertEquals(List.of(List.of("code", "student"), List.of("student", "big")), keys);
    }

    @Test void whatAnIndexCannotHoldIsRefusedWhereItIsWritten() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("optional", "unique (student, note);");
        refused.put("secret", "unique (student, secretWord);");
        refused.put("version", "unique (student, version);");
        refused.put("unbounded text", "unique (student, loose);");
        refused.put("the identity", "unique (student, id);");
        refused.put("a field twice", "unique (student, student);");
        refused.put("too long together", "unique (big, big2);");
        refused.put("the same key twice", "unique (student, code);\n  unique (code, student);");
        for (var entry : refused.entrySet()) {
            SemanticAnalysis result = analyze("  " + entry.getValue());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-VALID-014")),
                entry.getKey() + ": " + result.diagnostics());
        }
    }

    @Test void aFieldTheEntityDoesNotHaveIsAnUnknownName() {
        SemanticAnalysis result = analyze("  unique (student, nothing);");
        assertEquals("SIR-SYMBOL-002", result.diagnostics().stream().map(d -> d.code().value()).distinct().collect(Collectors.joining(",")));
    }

    @Test void aKeyNeedsAtLeastTwoFields() {
        // One field is a field's own `unique` modifier; the helper reports a source that does not parse as an assertion failure.
        AssertionError parse = org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> analyze("  unique (student);"));
        assertTrue(parse.getMessage().contains("SIR-PARSE"), parse.getMessage());
    }
}
