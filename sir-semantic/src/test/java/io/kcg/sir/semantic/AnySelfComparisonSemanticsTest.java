package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.DiagnosticSeverity;
import io.kcg.sir.semantic.api.ReferenceRole;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Same related-row binding is an error; matching field declarations on different rows are not. */
class AnySelfComparisonSemanticsTest {
    private static final String DECLARATIONS = """
            entity Reader persistent { identity id: Id generated auto; field name: Text; }
            entity Copy persistent {
              identity id: Id generated auto;
              field owner: Ref<Reader>;
              field price: Money;
            }
            entity Loan persistent {
              identity id: Id generated auto;
              field copy: Ref<Copy>;
              field reader: Ref<Reader>;
              field active: Flag;
            }
            view CopyView from Copy { field id: Id; }
            input SearchInput {
              field readerId: Id;
              field page: PageNo;
              field size: PageSize;
            }
            error BadPage;
            error Missing;
            """;
    private static final String BAD_COMPARISON = "reader == reader";
    private static final String BAD_ANY = "any(Loan, copy == item and " + BAD_COMPARISON + ")";

    @ParameterizedTest
    @ValueSource(strings = {
            "reader == reader",
            "reader != reader",
            "(reader) == ((reader))",
            "active == active",
            "id == id"
    })
    void rejectsOnlyTheSelfComparisonAndPointsToItsWholeExpression(String comparison) {
        rejectOnce(find("any(Loan, copy == item and " + comparison + ")"), comparison);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not any(Loan, copy == item and reader == reader)",
            "any(Loan, copy == item and (active == true or reader == reader))",
            "any(Loan, copy == item and not (reader == reader))"
    })
    void walksLogicalConditionsWithoutLosingTheRelatedEntity(String predicate) {
        rejectOnce(find(predicate), BAD_COMPARISON);
    }

    @Test
    void noOuterVariableIsRequiredForTheBadRelatedFieldComparison() {
        String source = find("any(Loan, copy == item and active == active)");
        rejectOnce(source, "active == active");
    }

    @Test
    void optionalFieldsAreRejectedAsSelfComparisonsWithoutAssumingTheyAreAlwaysTrue() {
        String source = find(BAD_ANY).replace("field reader: Ref<Reader>;", "field reader: Optional<Ref<Reader>>;");
        rejectOnce(source, BAD_COMPARISON);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryKinds")
    void allPublicQueryPredicatesUseTheSameCheck(String kind, String output, String workflow) {
        rejectOnce(software(output, workflow), BAD_COMPARISON);
    }

    static Stream<Arguments> queryKinds() {
        String predicate = " where " + BAD_ANY;
        String page = " Page input.page, input.size else BadPage";
        return Stream.of(
                Arguments.of("find", "Page<CopyView>", "find Copy" + predicate + page + " as rows; return rows;"),
                Arguments.of("exists", "Flag", "exists Copy" + predicate + " as found; return found;"),
                Arguments.of("count", "Serial", "count Copy" + predicate + " as total; return total;"),
                Arguments.of("sum", "Money", "sum Copy.price" + predicate + " as total; return total;"),
                Arguments.of("avg", "Money", "avg Copy.price" + predicate + " as total; return total;"),
                Arguments.of("sum by", "Page<SumGroup<Ref<Reader>>>",
                        "sum Copy.price by owner" + predicate + page + " as totals; return totals;"),
                Arguments.of("avg by", "Page<SumGroup<Ref<Reader>>>",
                        "avg Copy.price by owner" + predicate + page + " as totals; return totals;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "any(Loan, copy == item and reader == actor)",
            "any(Loan, copy == item and reader == wanted)",
            "any(Loan, item == copy and active == true)",
            "any(Reader, id == item.owner)",
            "any(Reader, item.owner == id and name == wanted.name)"
    })
    void neitherShadowingByItselfNorTheReverseConnectionIsAnError(String predicate) {
        assertClean(find(predicate));
    }

    @Test
    void theSameFieldDeclarationOnTwoDifferentRowsIsNotASelfComparison() {
        String source = TestSources.sir("""
                entity Node persistent {
                  identity id: Id generated auto;
                  field parent: Ref<Node>;
                  field code: Text;
                }
                view NodeView from Node { field id: Id; }
                error BadPage;
                capability Search {
                  output Page<NodeView>;
                  fails BadPage;
                  requires readonly;
                  expose query;
                  workflow {
                    find Node where any(Node, parent == item and code == item.code)
                      Page 1, 10 else BadPage as rows;
                    return rows;
                  }
                }
                """);
        var result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        var bindings = result.model().orElseThrow().referenceSiteBindings().all().stream()
                .filter(b -> b.site().text().equals("code"))
                .filter(b -> b.site().role() == ReferenceRole.EXISTS_CONDITION_FIELD
                        || b.site().role() == ReferenceRole.MEMBER_ACCESS).toList();
        assertEquals(2, bindings.size());
        assertEquals(bindings.get(0).targetSymbol(), bindings.get(1).targetSymbol());
        assertFalse(bindings.get(0).site().role() == bindings.get(1).site().role());
    }

    @Test
    void eachBadExpressionGetsExactlyOneDeterministicDiagnostic() {
        String source = find("any(Loan, copy == item and reader == reader and active != active)");
        var first = TestSources.analyze(source);
        var second = TestSources.analyze(source);
        assertEquals(List.of("SIR-VALID-001", "SIR-VALID-001"), TestSources.errorCodes(first));
        assertEquals(first.diagnostics(), second.diagnostics());
        assertEquals(List.of("reader == reader", "active != active"), first.diagnostics().stream()
                .map(d -> source.substring(source.offsetByCodePoints(0, d.primarySpan().start().codePointOffset()),
                        source.offsetByCodePoints(0, d.primarySpan().end().codePointOffset()))).toList());
    }

    @Test
    void sourcePositionsRemainCodePointBasedAfterNonBmpText() {
        rejectOnce("// 调用方 😀\n" + find(BAD_ANY), BAD_COMPARISON);
    }

    @Test
    void anOrdinaryColumnSelfComparisonKeepsOnlyItsExistingShapeDiagnostic() {
        var result = TestSources.analyze(find("item.price == item.price"));
        assertEquals(List.of("SIR-VALID-001"), TestSources.errorCodes(result));
        assertTrue(result.diagnostics().getFirst().message().contains("普通查询条件"));
    }

    @Test
    void missingConnectionKeepsItsExistingTypeDiagnostic() {
        var result = TestSources.analyze(find("any(Loan, active == true)"));
        assertEquals(List.of("SIR-TYPE-004"), TestSources.errorCodes(result));
    }

    @Test
    void anIllegalAnyPositionDoesNotAlsoProduceASelfComparisonDiagnostic() {
        String source = software("Page<CopyView>", "validate any(Loan, reader == reader) else Missing; "
                + "find Copy where true Page input.page, input.size else BadPage as rows; return rows;");
        var result = TestSources.analyze(source);
        assertTrue(TestSources.hasError(result, "SIR-FLOW-005"));
        assertFalse(TestSources.hasError(result, "SIR-VALID-001"));
    }

    @Test
    void aNestedIllegalAnyDoesNotAlsoProduceASelfComparisonDiagnostic() {
        var result = TestSources.analyze(find("any(Loan, copy == item and " + BAD_ANY + ")"));
        assertTrue(TestSources.hasError(result, "SIR-FLOW-006"));
        assertFalse(TestSources.hasError(result, "SIR-VALID-001"));
    }

    private static String find(String predicate) {
        return software("Page<CopyView>", "find Copy where " + predicate
                + " Page input.page, input.size else BadPage as rows; return rows;");
    }

    private static String software(String output, String workflow) {
        return TestSources.sir(DECLARATIONS + "\ncapability Search { actor Ref<Reader>; input SearchInput; output "
                + output + "; fails BadPage; fails Missing; requires readonly; expose query; workflow { "
                + "load Reader by input.readerId as reader else Missing; "
                + "load Reader by input.readerId as wanted else Missing; " + workflow + " } }");
    }

    private static void assertClean(String source) {
        var result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    private static void rejectOnce(String source, String expression) {
        var result = TestSources.analyze(source);
        assertFalse(result.isSuccess(), () -> "expected rejection: " + source);
        assertEquals(List.of("SIR-VALID-001"), TestSources.errorCodes(result), () -> result.diagnostics().toString());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        int at = source.indexOf(expression);
        assertTrue(at >= 0);
        assertEquals(source.codePointCount(0, at), diagnostic.primarySpan().start().codePointOffset());
        assertEquals(source.codePointCount(0, at + expression.length()), diagnostic.primarySpan().end().codePointOffset());
        assertEquals("test", diagnostic.primarySpan().source().value());
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.message().contains("关联实体字段 Loan."), diagnostic::message);
        assertTrue(diagnostic.message().contains("外层变量"), diagnostic::message);
        assertTrue(diagnostic.message().contains("不与字段同名"), diagnostic::message);
        assertTrue(diagnostic.related().isEmpty());
        assertTrue(diagnostic.fixes().isEmpty());
    }
}
