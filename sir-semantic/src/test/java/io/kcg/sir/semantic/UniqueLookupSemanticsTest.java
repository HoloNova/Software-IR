package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules of the {@code unique} field marker and of a load that looks a row up by such a field.
 *
 * <p>The legal source is asserted clean and checked for the normalized shape the target needs. Each
 * counterexample is built by replacing one named fragment of that source and must be refused by
 * exactly the rule under test, so an edit that fails to apply fails the test instead of silently
 * analyzing the legal source again.
 */
class UniqueLookupSemanticsTest {
    private static final String FIXTURE = "valid/unique-lookup.sir";

    @Test
    void theLegalSourceAnalyzesCleanAndKeepsWhatTheTargetNeeds() {
        SemanticAnalysis result = TestSources.analyzeResource(FIXTURE);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());

        NormalizedEntity account = result.model().orElseThrow().declarations().stream()
                .filter(NormalizedEntity.class::isInstance)
                .map(NormalizedEntity.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(field(account, "name").unique());
        assertTrue(field(account, "badge").unique());
        assertTrue(field(account, "token").unique());
        assertFalse(field(account, "nickname").unique());

        NormalizedStep.LoadStep byName = load(result, "FindAccountByName");
        assertEquals(field(account, "name").id(), byName.keyField().orElseThrow());
        assertTrue(load(result, "GetAccount").keyField().isEmpty(), "a load without a field name is by identity");
    }

    @Test
    void aLoadMayOnlyLookUpByAFieldDeclaredUnique() {
        assertOnly("SIR-VALID-006", replace("load Account by name: input.name as account else AccountNotFound;\n        return account;\n      }\n    }\n    capability GetAccount",
                "load Account by nickname: input.name as account else AccountNotFound;\n        return account;\n      }\n    }\n    capability GetAccount"));
    }

    @Test
    void theIdentityIsNotNamedAsAKeyBecauseThatFormIsTheLoadWithoutAFieldName() {
        assertOnly("SIR-VALID-006", replace("load Account by input.id as account else AccountNotFound;",
                "load Account by id: input.id as account else AccountNotFound;"));
    }

    @Test
    void aKeyThatIsNoFieldOfTheEntityIsAnUnknownName() {
        SemanticAnalysis result = TestSources.analyze(replace("load Account by badge: input.badge as account",
                "load Account by nothing: input.badge as account"));

        assertTrue(TestSources.hasError(result, "SIR-SYMBOL-002"), () -> result.diagnostics().toString());
    }

    @Test
    void theKeyValueMustFitTheFieldItIsComparedWith() {
        assertOnly("SIR-TYPE-001", replace("input FindByBadgeInput { field badge: Int64; }",
                "input FindByBadgeInput { field badge: String; }"));
    }

    @Test
    void onlyTextIntegersAndUuidsCanBeUnique() {
        assertOnly("SIR-TYPE-001", replace("field email: String where notBlank unique;", "field email: Optional<String> unique;"));
        assertOnly("SIR-TYPE-001", replace("field email: String where notBlank unique;", "field email: Decimal unique;"));
        assertOnly("SIR-TYPE-001", replace("field email: String where notBlank unique;", "field email: Boolean unique;"));
    }

    @Test
    void theVersionFieldIsNotUnique() {
        // the versioned entity also makes the fixture's keyed load-then-update illegal, so this asserts the one rule, not the count
        SemanticAnalysis result = TestSources.analyze(replace("field token: Uuid unique;", "field token: Uuid unique;\n      field version: Int64 versioned unique;"));

        assertTrue(TestSources.hasError(result, "SIR-VALID-002"), () -> result.diagnostics().toString());
    }

    @Test
    void aVersionedEntityCannotBeLoadedByAFieldAndThenUpdatedBecauseThatLoadCannotTakeTheRowLock() {
        SemanticAnalysis result = TestSources.analyze(replace("field token: Uuid unique;", "field token: Uuid unique;\n      field version: Int64 versioned;"));

        assertTrue(TestSources.hasError(result, "SIR-VALID-006"), () -> result.diagnostics().toString());
    }

    private static NormalizedStep.LoadStep load(SemanticAnalysis result, String capabilityName) {
        NormalizedCapability capability = result.model().orElseThrow().declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .filter(value -> value.name().equals(capabilityName))
                .findFirst()
                .orElseThrow();
        return (NormalizedStep.LoadStep) capability.workflow().steps().get(0);
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
