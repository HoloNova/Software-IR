package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SumGroupType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One exact total per distinct reference key, bounded to a page.
 *
 * <p>The key is deliberately restricted to a direct, non-optional reference: only then do
 * {@code COUNT(DISTINCT key)} and {@code GROUP BY key} describe the same groups.
 */
class GroupedSumSemanticsTest {
    private static String source(String keyDeclaration, String measurement, String groupKey, String requires) {
        return TestSources.sir("""
                entity User persistent { identity id: Id generated auto; }
                entity Category persistent { identity id: Id generated auto; field owner: Ref<User>; field label: Text; }
                entity Entry persistent {
                  identity id: Id generated auto;
                  field owner: Ref<User>;
                  %s
                  %s
                  field occurredAt: Moment;
                }
                input TotalsInput { field page: PageNo; field size: PageSize; }
                error BadPage;
                capability Totals {
                  actor Ref<User>; input TotalsInput; output Page<SumGroup<Ref<Category>>>;
                  fails BadPage; %s expose query;
                  workflow {
                    sum Entry.amount by %s where item.owner == actor Page input.page, input.size else BadPage as totals;
                    return totals;
                  }
                }
                """.formatted(keyDeclaration, measurement, requires, groupKey));
    }

    private static String valid(String keyDeclaration, String groupKey) {
        return source(keyDeclaration, "field amount: Money;", groupKey, "requires authenticated; requires readonly;");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"owner == item.owner","id == actor","id == input.page"})
    void relatedIdentityCannotBecomeAnUncorrelatedOrWrongTargetJoin(String condition) {
        var analysis=TestSources.analyze(TestSources.resource("valid/decimal-group.sir").replace("id == item.category",condition));
        assertFalse(analysis.isSuccess(),analysis.diagnostics().toString());
    }

    @Test void reverseIdentityCorrelationAcceptsEitherOperandOrder() {
        var analysis=TestSources.analyze(TestSources.resource("valid/decimal-group.sir").replace("id == item.category","item.category == id"));
        assertTrue(analysis.isSuccess(),analysis.diagnostics().toString());
    }

    @Test void sharedFixtureIsAcceptedWithScopeAndRelatedOwnerFilter() {
        var analyzed = TestSources.analyzeResource("valid/decimal-group.sir");
        assertTrue(analyzed.isSuccess(), () -> analyzed.diagnostics().toString());
    }

    @Test void aGroupedTotalBindsItsKeyOnceAndTypesAPageOfGroups() {
        var key = "field category: Ref<Category>;";
        var analyzed = TestSources.analyze(valid(key, "category"));
        assertTrue(analyzed.isSuccess(), () -> analyzed.diagnostics().toString());
        var model = ((io.kcg.sir.semantic.api.SemanticAnalysis.Success) analyzed).normalizedModel();
        var capability = model.declarations().stream().filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast).filter(c -> c.name().equals("Totals")).findFirst().orElseThrow();
        var step = (NormalizedStep.GroupedSumStep) capability.workflow().steps().getFirst();

        assertEquals("Category", ((SumGroupType) ((PageType) capability.outputType()).element()).key().entityName());
        var category = model.symbols().all().stream()
                .filter(symbol -> symbol.name().equals("Category") && symbol.kind() == io.kcg.sir.semantic.symbol.SymbolKind.ENTITY)
                .findFirst().orElseThrow();
        assertEquals(category.id(), ((SumGroupType) ((PageType) capability.outputType()).element()).key().entityId());
        assertNotEquals(step.keySymbol(), step.fieldSymbol());
        assertEquals("category", model.symbols().byId(step.keySymbol()).orElseThrow().name());
        assertEquals("amount", model.symbols().byId(step.fieldSymbol()).orElseThrow().name());
        assertTrue(model.referenceSiteBindings().all().stream()
                .anyMatch(binding -> binding.site().role() == ReferenceRole.GROUP_KEY_FIELD
                        && binding.targetSymbol().equals(step.keySymbol())),
                () -> model.referenceSiteBindings().all().toString());

    }

    @ParameterizedTest
    @ValueSource(strings = {"field label: Text;", "field owner2: Ref<User>;"})
    void onlyADirectReferenceKeyGroups(String keyDeclaration) {
        // One key is not a reference at all, the other references another entity than the declared group: neither may
        // type-check, and neither is silently retyped to the declared output.
        var name = keyDeclaration.contains("label") ? "label" : "owner2";
        var analyzed = TestSources.analyze(valid(keyDeclaration, name));
        assertFalse(analyzed.isSuccess());
        assertTrue(analyzed.diagnostics().stream().anyMatch(d -> d.code().value().startsWith("SIR-TYPE")),
                () -> analyzed.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"field amount: Text;", "field amount: Count;", "field amount: Day;"})
    void onlyADecimalMeasurementSums(String measurement) {
        var analyzed = TestSources.analyze(source("field category: Ref<Category>;", measurement, "category",
                "requires authenticated; requires readonly;"));
        assertFalse(analyzed.isSuccess());
        assertTrue(analyzed.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-TYPE-001")),
                () -> analyzed.diagnostics().toString());
    }

    @Test void aGroupedTotalIsStillAReadonlyQuery() {
        var analyzed = TestSources.analyze(source("field category: Ref<Category>;", "field amount: Money;",
                "category", "requires authenticated;"));
        assertFalse(analyzed.isSuccess());
        assertTrue(analyzed.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-FLOW-004")),
                () -> analyzed.diagnostics().toString());
    }

    @Test void optionalKeysCannotLoseTheirNullBucketInTheGroupCount() {
        var analyzed = TestSources.analyze(valid("field category: Optional<Ref<Category>>;", "category"));
        assertFalse(analyzed.isSuccess());
        assertTrue(TestSources.hasError(analyzed, "SIR-TYPE-001"), analyzed.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Page input.page, input.page else BadPage", "Page 1, 10 else BadPage", "Page input.page, input.size else Missing"})
    void paginationMustUseDistinctInputFieldsAndADeclaredFailure(String pagination) {
        String text = valid("field category: Ref<Category>;", "category")
            .replace("Page input.page, input.size else BadPage", pagination);
        var result = TestSources.analyze(text);
        assertFalse(result.isSuccess(), result.diagnostics().toString());
    }

    @Test void aGroupedCarrierIsNotAStoredOrRequestValue() {
        String text = valid("field category: Ref<Category>;", "category")
            .replace("field occurredAt: Moment;", "field occurredAt: Moment; field bad: SumGroup<Ref<Category>>;");
        assertFalse(TestSources.analyze(text).isSuccess());
    }

    @Test void missingPaginationIsAParseFailureNotADroppedStep() {
        String text = valid("field category: Ref<Category>;", "category")
            .replace("Page input.page, input.size else BadPage ", "");
        assertThrows(AssertionError.class, () -> TestSources.parse(text));
    }

    @Test void aSecretMeasurementCannotBeSummed() {
        var analyzed = TestSources.analyze(source("field category: Ref<Category>;",
                "field amount: Money secret;", "category", "requires authenticated; requires readonly;"));
        assertFalse(analyzed.isSuccess());
        assertTrue(analyzed.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-VALID-001")),
                () -> analyzed.diagnostics().toString());
    }
}
