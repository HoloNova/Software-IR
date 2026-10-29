package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.DiagnosticSeverity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** User-facing explanations must preserve the rejection, source location and query-scope boundary. */
class QueryDiagnosticMessageTest {
    private static final String DECLARATIONS = """
            entity Owner persistent {
              identity id: Id generated auto;
              field name: Text;
            }
            entity Entry persistent {
              identity id: Id generated auto;
              field owner: Ref<Owner>;
              field amount: Money;
              field title: Text;
            }
            entity Link persistent {
              identity id: Id generated auto;
              field entry: Ref<Entry>;
              field active: Flag;
            }
            view EntryView from Entry {
              field id: Id;
              field owner: Ref<Owner>;
              field title: Text;
            }
            input SearchInput {
              field minimum: Money;
              field keyword: Text;
              field page: PageNo;
              field size: PageSize;
            }
            error BadPage;
            error TooMany;
            """;
    private static final String INVALID_COMPARISON = "input.minimum < item.amount";
    private static final String STRING_MATCH = "item.title containsLiteral input.keyword";

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordinaryQueries")
    void sharedComparisonMessageExplainsOrdinaryQueriesWithoutPrescribingAnySyntax(
            String kind, String output, String clauses, String workflow) {
        String source = software(DECLARATIONS, output, clauses, workflow);
        Diagnostic diagnostic = rejectOnce(source, "SIR-VALID-001", INVALID_COMPARISON);

        assertTrue(diagnostic.message().contains("普通查询条件"), diagnostic::message);
        assertTrue(diagnostic.message().contains("不含 any 内部"), diagnostic::message);
        assertTrue(diagnostic.message().contains("item.<直接字段>"), diagnostic::message);
        assertTrue(diagnostic.message().contains("外层根行"), diagnostic::message);
        assertFalse(diagnostic.message().startsWith("find 谓词"), diagnostic::message);
    }

    static Stream<Arguments> ordinaryQueries() {
        String paged = "fails BadPage; requires readonly; expose query;";
        String readonly = "requires readonly; expose query;";
        String predicate = " where " + INVALID_COMPARISON;
        String page = " Page input.page, input.size else BadPage";
        return Stream.of(
                Arguments.of("find", "Page<EntryView>", paged,
                        "find Entry" + predicate + page + " as rows; return rows;"),
                Arguments.of("exists", "Flag", readonly,
                        "exists Entry" + predicate + " as found; return found;"),
                Arguments.of("count", "Serial", readonly,
                        "count Entry" + predicate + " as total; return total;"),
                Arguments.of("sum", "Money", readonly,
                        "sum Entry.amount" + predicate + " as total; return total;"),
                Arguments.of("avg", "Money", readonly,
                        "avg Entry.amount" + predicate + " as total; return total;"),
                Arguments.of("sum by", "Page<SumGroup<Ref<Owner>>>", paged,
                        "sum Entry.amount by owner" + predicate + page + " as totals; return totals;"),
                Arguments.of("avg by", "Page<SumGroup<Ref<Owner>>>", paged,
                        "avg Entry.amount by owner" + predicate + page + " as totals; return totals;"),
                Arguments.of("each", "Unit", "fails TooMany; requires atomic; expose command;",
                        "each Entry" + predicate + " order by id ascending limit 10 else TooMany as row { } return unit;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "any(Link, active == true)",
            "any(Link, entry == item or active == true)"
    })
    void missingAnyConnectionExplainsBothSupportedDirectionsAndTheOuterItem(String condition) {
        Diagnostic diagnostic = rejectOnce(find(DECLARATIONS, condition), "SIR-TYPE-004", condition);

        assertTrue(diagnostic.message().contains("Ref 字段 == item"), diagnostic::message);
        assertTrue(diagnostic.message().contains("主键 == item.引用字段"), diagnostic::message);
        assertTrue(diagnostic.message().contains("裸字段属于关联实体"), diagnostic::message);
        assertTrue(diagnostic.message().contains("外层根行"), diagnostic::message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "any(Link, entry == item)",
            "any(Owner, id == item.owner)",
            "any(Link, entry == item and active == true)",
            "any(Owner, id == item.owner and name == input.keyword)"
    })
    void legalAnyDirectionsAreNotSubjectedToOrdinaryComparisonShape(String condition) {
        assertClean(find(DECLARATIONS, condition));
    }

    @ParameterizedTest
    @ValueSource(strings = {"input.keyword is none or ", "input.keyword is present and "})
    void optionalRightOperandRemainsRejectedAfterEitherNullTest(String prefix) {
        String declarations = DECLARATIONS.replace("field keyword: Text;", "field keyword: Optional<Text>;");
        Diagnostic diagnostic = rejectOnce(find(declarations, prefix + STRING_MATCH), "SIR-TYPE-001", STRING_MATCH);

        assertTrue(diagnostic.message().contains("非 Optional 文本值"), diagnostic::message);
        assertTrue(diagnostic.message().contains("is none/is present"), diagnostic::message);
    }

    @Test
    void optionalTextColumnStillAcceptsARequiredTextParameter() {
        String declarations = DECLARATIONS.replace("field title: Text;", "field title: Optional<Text>;");
        assertClean(find(declarations, STRING_MATCH));
    }

    @Test
    void optionalOrdinaryEqualityIsNotAccidentallyRestrictedLikeStringMatching() {
        String declarations = DECLARATIONS.replace("field keyword: Text;", "field keyword: Optional<Text>;");
        assertClean(find(declarations, "input.keyword is none or item.title == input.keyword"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Id", "Text"})
    void referenceProjectionMismatchExplainsWhyAPrimaryKeyIsNotAReference(String wrongType) {
        String declarations = DECLARATIONS.replace("  field owner: Ref<Owner>;\n  field title: Text;",
                        "  field owner: " + wrongType + ";\n  field title: Text;");
        String source = TestSources.sir(declarations);
        Diagnostic diagnostic = rejectOnce(source, "SIR-TYPE-001", wrongType,
                source.indexOf("field owner: " + wrongType, source.indexOf("view EntryView from Entry")));

        assertTrue(diagnostic.message().contains("Ref<实体>"), diagnostic::message);
        assertTrue(diagnostic.message().contains("主键编号与实体引用"), diagnostic::message);
        assertFalse(diagnostic.message().contains("HTTP"), diagnostic::message);
    }

    @Test
    void scalarProjectionMismatchKeepsItsOriginalMessageWithoutAReferenceHint() {
        String declarations = DECLARATIONS + "view WrongView from Entry { field amount: Text; }";
        String source = TestSources.sir(declarations);
        Diagnostic diagnostic = rejectOnce(source, "SIR-TYPE-001", "Text", source.indexOf("view WrongView"));

        assertEquals("投影字段类型与实体字段不一致: WrongView.amount 声明为 STRING 但实体字段为 DECIMAL", diagnostic.message());
    }

    @Test
    void matchingReferenceProjectionAndNestedViewBothRemainValid() {
        assertClean(TestSources.sir(DECLARATIONS));
        String nested = DECLARATIONS
                + "view OwnerSummary from Owner { field id: Id; field name: Text; }\n"
                + "view NestedEntry from Entry { field owner: OwnerSummary; }";
        assertClean(TestSources.sir(nested));
    }

    private static String find(String declarations, String condition) {
        return software(declarations, "Page<EntryView>", "fails BadPage; requires readonly; expose query;",
                "find Entry where " + condition + " Page input.page, input.size else BadPage as rows; return rows;");
    }

    private static String software(String declarations, String output, String clauses, String workflow) {
        return TestSources.sir(declarations + "\ncapability Check {\ninput SearchInput;\noutput " + output + ";\n"
                + clauses + "\nworkflow {\n" + workflow + "\n}\n}\n");
    }

    private static void assertClean(String source) {
        var result = TestSources.analyze(source);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    private static Diagnostic rejectOnce(String source, String code, String needle) {
        return rejectOnce(source, code, needle, 0);
    }

    private static Diagnostic rejectOnce(String source, String code, String needle, int fromIndex) {
        var result = TestSources.analyze(source);
        assertFalse(result.isSuccess(), () -> "expected rejection: " + source);
        assertEquals(List.of(code), TestSources.errorCodes(result), () -> result.diagnostics().toString());
        assertEquals(1, result.diagnostics().size(), () -> result.diagnostics().toString());
        Diagnostic diagnostic = result.diagnostics().getFirst();
        int index = source.indexOf(needle, fromIndex);
        assertTrue(index >= 0, () -> "missing source fragment: " + needle);
        assertEquals(source.codePointCount(0, index), diagnostic.primarySpan().start().codePointOffset());
        assertEquals(source.codePointCount(0, index + needle.length()), diagnostic.primarySpan().end().codePointOffset());
        assertEquals("test", diagnostic.primarySpan().source().value());
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.related().isEmpty());
        assertTrue(diagnostic.fixes().isEmpty());
        return diagnostic;
    }
}
