package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCredential;
import io.kcg.sir.semantic.model.NormalizedEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of the entity-closing credential clause. The legal source is asserted clean and checked for
 * what the target needs; each counterexample replaces one named fragment of it and must be refused by
 * exactly the rule under test.
 */
class CredentialSemanticsTest {
    private static final String FIXTURE = "valid/bearer-credential.sir";
    private static final String CLAUSE = "credential subject owner expires expiresAt;";

    @Test
    void theLegalSourceAnalyzesCleanAndKeepsTheClauseOnTheEntityThatDeclaresIt() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());

        NormalizedEntity token = entity(result, "AccessToken");
        NormalizedCredential credential = token.credential().orElseThrow();
        assertEquals(token.fields().get(0).id(), credential.subjectField());
        assertEquals(token.fields().get(1).id(), credential.expiryField());
        assertTrue(entity(result, "User").credential().isEmpty());
    }

    @Test
    void theTokenIsTheKeySoItMustBeAnUnguessableUuid() {
        SemanticAnalysis result = TestSources.analyze(replace("identity id: Token generated uuid;\n      field owner", "identity id: Id generated auto;\n      field owner"));

        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"), () -> result.diagnostics().toString());
    }

    @Test
    void theSubjectIsAReferenceAndTheExpiryADateTime() {
        // the fixture's own bindings stop fitting the changed field too, so these assert the rule, not the count
        for (String source : List.of(
                replace("field owner: Ref<User>;\n      field expiresAt", "field owner: Text;\n      field expiresAt"),
                replace("field expiresAt: Moment;\n      credential", "field expiresAt: Day;\n      credential"))) {
            SemanticAnalysis result = TestSources.analyze(source);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("subject") || d.message().contains("expires")),
                    () -> result.diagnostics().toString());
        }
    }

    @Test
    void thePrimaryKeyIsNeitherTheSubjectNorTheExpiry() {
        assertOnly("SIR-TYPE-001", replace(CLAUSE, "credential subject id expires expiresAt;"));
        assertOnly("SIR-TYPE-001", replace(CLAUSE, "credential subject owner expires id;"));
    }

    @Test
    void aNameThatIsNoFieldOfTheEntityIsAnUnknownName() {
        SemanticAnalysis result = TestSources.analyze(replace(CLAUSE, "credential subject nobody expires expiresAt;"));

        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"), () -> result.diagnostics().toString());
    }

    @Test
    void aSubjectIsKnownByOneKindOfTokenOnly() {
        SemanticAnalysis result = TestSources.analyze(replace("    entity Note persistent {",
                "    entity SecondToken persistent {\n      identity id: Token generated uuid;\n      field owner: Ref<User>;\n      field expiresAt: Moment;\n      "
                        + CLAUSE + "\n    }\n    entity Note persistent {"));

        assertTrue(TestSources.hasError(result, "SIR-VALID-009"), () -> result.diagnostics().toString());
    }

    @Test
    void authenticatedCapabilitiesAreAllBackedByCredentialsOrNoneIs() {
        String mixed = replace("    entity Note persistent {", "    entity Admin persistent {\n      identity id: Id generated auto;\n      field name: Text;\n    }\n    entity Note persistent {")
                .replace("    input LoginInput {", "    capability AdminOnly {\n      actor Ref<Admin>;\n      input LoginInput;\n      output NoteView;\n      requires authenticated;\n      expose query;\n      workflow {\n        load Note by 1 as note else BadLogin;\n        return note;\n      }\n    }\n    input LoginInput {");
        SemanticAnalysis result = TestSources.analyze(mixed);

        assertTrue(TestSources.hasError(result, "SIR-VALID-009"), () -> result.diagnostics().toString());
    }

    private static NormalizedEntity entity(SemanticAnalysis result, String name) {
        return result.model().orElseThrow().declarations().stream()
                .filter(NormalizedEntity.class::isInstance)
                .map(NormalizedEntity.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertOnly(String code, String source) {
        SemanticAnalysis result = TestSources.analyze(source);

        assertEquals(List.of(code), TestSources.errorCodes(result), () -> result.diagnostics().toString());
    }

    private static String replace(String fragment, String replacement) {
        String source = TestSources.resource(FIXTURE);
        assertTrue(source.contains(fragment), "the fragment to replace is missing: " + fragment);
        return source.replace(fragment, replacement);
    }
}
