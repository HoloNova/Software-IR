package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of the {@code secret} field marker and of {@code matches}, the one way to read a secret.
 *
 * <p>The legal source is asserted clean; each counterexample replaces one named fragment of it and must
 * be refused by exactly the rule under test, so an edit that fails to apply fails the test instead of
 * silently analyzing the legal source again.
 */
class SecretFieldSemanticsTest {
    private static final String FIXTURE = "valid/secret-field.sir";
    private static final String MATCHES = "validate account.passwordHash matches input.password else BadLogin;";

    @Test
    void theLegalSourceAnalyzesCleanAndKeepsTheMarkerOnTheFieldThatDeclaresIt() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());

        NormalizedEntity account = result.model().orElseThrow().declarations().stream()
                .filter(NormalizedEntity.class::isInstance)
                .map(NormalizedEntity.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(field(account, "passwordHash").secret());
        assertFalse(field(account, "name").secret());
    }

    @Test
    void onlyTextCanBeSecret() {
        // the string binding in the fixture no longer fits an Int32 field either, so this asserts the rule, not the count
        SemanticAnalysis result = TestSources.analyze(replace("field passwordHash: Secret;", "field passwordHash: Count secret;"));

        assertTrue(TestSources.hasError(result, "SIR-TYPE-001"), () -> result.diagnostics().toString());
    }

    @Test
    void aSecretCannotAlsoBeUniqueVersionedOrConstrained() {
        assertOnly("SIR-VALID-008", replace("field passwordHash: Secret;", "field passwordHash: Secret unique;"));
        assertOnly("SIR-VALID-008", replace("field passwordHash: Secret;", "field passwordHash: Secret where notBlank;"));
        SemanticAnalysis versioned = TestSources.analyze(replace("field passwordHash: Secret;", "field passwordHash: Secret;\n      field version: Version secret;"));
        assertTrue(TestSources.hasError(versioned, "SIR-VALID-008"), () -> versioned.diagnostics().toString());
    }

    @Test
    void aSecretIsNeverProjectedIntoAResponse() {
        assertOnly("SIR-VALID-008", replace("field name: Text;\n    }\n    input RegisterInput",
                "field name: Text;\n      field passwordHash: Text;\n    }\n    input RegisterInput"));
    }

    @Test
    void aSecretCanOnlyBeReadAsTheLeftSideOfMatches() {
        assertOnly("SIR-VALID-008", replace(MATCHES, "validate account.passwordHash == input.password else BadLogin;"));
        // both sides are wrong here (the left is no secret, the right reads one), so this asserts the rule, not the count
        SemanticAnalysis reversed = TestSources.analyze(replace(MATCHES, "validate input.password matches account.passwordHash else BadLogin;"));
        assertTrue(TestSources.hasError(reversed, "SIR-VALID-008"), () -> reversed.diagnostics().toString());
    }

    @Test
    void matchesNeedsASecretFieldOnTheLeftAndTextOnBothSides() {
        assertOnly("SIR-VALID-008", replace(MATCHES, "validate account.name matches input.password else BadLogin;"));
        assertOnly("SIR-VALID-008", replace(MATCHES, "validate input.name matches input.password else BadLogin;"));
        assertOnly("SIR-TYPE-001", replace(MATCHES, "validate account.passwordHash matches 5 else BadLogin;"));
    }

    @Test
    void aSecretCannotBeComparedWithItself() {
        assertOnly("SIR-VALID-008", replace(MATCHES, MATCHES + "\n        validate account.passwordHash matches account.passwordHash else BadLogin;"));
    }

    private static NormalizedField field(NormalizedEntity entity, String name) {
        return entity.fields().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
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
