package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSiteBinding;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.model.NormalizedViewField;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.PageType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SirType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Q9 semantic contract for the query slice: the {@code view} projection, the
 * {@code Page<T>} output, the {@code find} ordering and pagination clauses, and
 * the {@code containsLiteral} string match.
 *
 * <p>Every negative case pins its diagnostic code, its count and its source
 * offset, because a rule that fires in the wrong phase or at the wrong span is
 * not the rule the work order describes.
 */
class QuerySliceSemanticsTest {

    private static final String BASE = """
            entity Course persistent {
              identity id: Int64 generated auto;
              field code: String where notBlank, length(1, 32);
              field name: String where notBlank, length(1, 100);
              field description: Optional<String> where length(0, 500);
              field capacity: Int32 where min(1);
            }
            view CourseSummary from Course {
              field code: String;
              field name: String;
              field capacity: Int32;
            }
            input SearchCoursesInput {
              field keyword: String where notBlank, length(1, 50);
              field page: Int32;
              field size: Int32;
            }
            error InvalidPageParam;
            capability SearchCourses {
              input SearchCoursesInput;
              output Page<CourseSummary>;
              fails InvalidPageParam;
              requires readonly;
              expose query;
              workflow {
                find Course
                  where item.name containsLiteral input.keyword
                  order by code ascending, id ascending
                  Page input.page, input.size else InvalidPageParam
                  as courses;
                return courses;
              }
            }
            """;

    @Test
    void querySliceAnalyzesSuccessfully() {
        SemanticAnalysis result = analyze(BASE);
        assertTrue(result.isSuccess(), () -> TestSources.errorCodes(result).toString());
    }

    @Test
    void viewDeclarationCarriesItsSourceEntityAndProjectedFields() {
        NormalizedView view = view(analyze(BASE).model().orElseThrow());

        SymbolId course = entitySymbol(analyze(BASE).model().orElseThrow(), "Course");
        assertEquals(course, view.sourceEntity());
        assertEquals(List.of("code", "name", "capacity"), view.fields().stream().map(NormalizedViewField::name).toList());
    }

    @Test
    void eachProjectedFieldKeepsTheIdentityOfTheEntityFieldItReads() {
        NormalizedSemanticModel model = analyze(BASE).model().orElseThrow();
        NormalizedView view = view(model);

        for (NormalizedViewField field : view.fields()) {
            Symbol source = model.symbols().byId(field.sourceField()).orElseThrow();
            assertEquals(field.name(), source.name());
            assertTrue(field.sourceField().value().contains("/entity/Course/field/"), field.sourceField().value());
            assertTrue(field.id().value().contains("/view/CourseSummary/field/"), field.id().value());
        }
    }

    @Test
    void projectedFieldsAreTypedAndCarryNoConstraints() {
        NormalizedSemanticModel model = analyze(BASE).model().orElseThrow();
        NormalizedView view = view(model);

        assertEquals(
                List.of(PrimitiveType.STRING, PrimitiveType.STRING, PrimitiveType.INT32),
                view.fields().stream().map(NormalizedViewField::type).toList());
    }

    @Test
    void pagedFindCarriesOrderKeysPageSourcesAndTheDeclaredError() {
        NormalizedStep.FindStep find = pagedFind(analyze(BASE).model().orElseThrow());
        NormalizedSemanticModel model = analyze(BASE).model().orElseThrow();
        SymbolId course = entitySymbol(model, "Course");

        assertEquals(course, find.entitySymbol());
        assertEquals(2, find.orderKeys().size());
        assertEquals("code", model.symbols().byId(find.orderKeys().get(0).fieldSymbol()).orElseThrow().name());
        assertEquals("id", model.symbols().byId(find.orderKeys().get(1).fieldSymbol()).orElseThrow().name());
        assertTrue(find.orderKeys().stream().noneMatch(NormalizedStep.OrderKey::descending));

        assertEquals("page", model.symbols().byId(find.page().orElseThrow().pageField()).orElseThrow().name());
        assertEquals("size", model.symbols().byId(find.page().orElseThrow().sizeField()).orElseThrow().name());
        assertEquals("InvalidPageParam", model.symbols().byId(find.page().orElseThrow().errorSymbol()).orElseThrow().name());
    }

    @Test
    void pagedFindResultIsAPageOfTheDeclaredView() {
        NormalizedSemanticModel model = analyze(BASE).model().orElseThrow();
        NormalizedStep.FindStep find = pagedFind(model);
        NormalizedCapability capability = capability(model, "SearchCourses");

        PageType page = assertInstanceOf(PageType.class, capability.outputType());
        assertInstanceOf(NormalizedView.class, view(model));
        assertTrue(page.element() instanceof io.kcg.sir.semantic.type.DeclaredType);

        Symbol resultSymbol = model.symbols().byId(find.resultVariable()).orElseThrow();
        SirType resultType = assertInstanceOf(Symbol.VariableSymbol.class, resultSymbol).type();
        assertEquals(capability.outputType(), resultType);
    }

    @Test
    void containsLiteralStaysAnExplicitOperatorInTheNormalizedPredicate() {
        NormalizedExpression.BinaryExpression predicate = assertInstanceOf(
                NormalizedExpression.BinaryExpression.class, pagedFind(analyze(BASE).model().orElseThrow()).predicate());

        assertEquals(AstBinaryOperator.CONTAINS_LITERAL, predicate.operator());
        assertEquals(PrimitiveType.BOOLEAN, predicate.type());
        assertInstanceOf(NormalizedExpression.MemberExpression.class, predicate.left());
    }

    @Test
    void querySliceExercisesTheFourNewReferenceRoles() {
        NormalizedSemanticModel model = analyze(BASE).model().orElseThrow();
        Set<ReferenceRole> present = EnumSet.noneOf(ReferenceRole.class);
        for (ReferenceSiteBinding binding : model.referenceSiteBindings().all()) {
            present.add(binding.site().role());
        }

        assertTrue(present.containsAll(List.of(
                ReferenceRole.VIEW_SOURCE_ENTITY,
                ReferenceRole.VIEW_FIELD,
                ReferenceRole.ORDER_FIELD,
                ReferenceRole.PAGE_ERROR)));
    }

    @Test
    void orderingByANullableComparableFieldIsAllowed() {
        SemanticAnalysis result = analyze(BASE.replace(
                "order by code ascending, id ascending", "order by description ascending"));
        assertTrue(result.isSuccess(), () -> TestSources.errorCodes(result).toString());
    }

    @Test
    void descendingDirectionSurvivesNormalization() {
        NormalizedSemanticModel model = analyze(BASE.replace(
                        "order by code ascending, id ascending", "order by code descending, name ascending"))
                .model()
                .orElseThrow();
        NormalizedStep.FindStep find = pagedFind(model);

        assertTrue(find.orderKeys().getFirst().descending());
        assertFalse(find.orderKeys().get(1).descending());
    }

    @Test
    void viewSourceMustBeAnEntity() {
        assertRejected(
                BASE.replace("view CourseSummary from Course", "view CourseSummary from SearchCoursesInput"),
                "SIR-TYPE-001",
                "SearchCoursesInput {",
                1);
    }

    @Test
    void viewSourceMustBeDefined() {
        assertRejected(
                BASE.replace("view CourseSummary from Course", "view CourseSummary from Missing"),
                "SIR-SYMBOL-002",
                "Missing",
                1);
    }

    @Test
    void viewFieldMustExistOnTheSourceEntity() {
        assertRejected(
                BASE.replace("field capacity: Int32;\n}", "field capacity: Int32;\n  field missing: String;\n}"),
                "SIR-SYMBOL-002",
                "missing: String",
                1);
    }

    @Test
    void viewFieldTypeMustMatchTheEntityFieldType() {
        assertRejected(
                BASE.replace("field capacity: Int32;\n}", "field capacity: Int64;\n}"),
                "SIR-TYPE-001",
                "Int64;\n}",
                1);
    }

    @Test
    void viewFieldConstraintsAreRejectedBecauseResponsesAreNotValidated() {
        assertRejected(
                BASE.replace("field name: String;\n  field capacity", "field name: String where notBlank;\n  field capacity"),
                "SIR-VALID-001",
                "notBlank;\n  field capacity",
                1);
    }

    @Test
    void viewFieldNamesMustBeUnique() {
        assertRejected(
                BASE.replace("field code: String;\n  field name", "field code: String;\n  field code: String;\n  field name"),
                "SIR-SYMBOL-001",
                "field code: String;\n  field name",
                1);
    }

    @Test
    void pageElementMustBeAView() {
        assertRejected(
                BASE.replace("output Page<CourseSummary>", "output Page<SearchCoursesInput>"),
                "SIR-TYPE-001",
                "Page<SearchCoursesInput>",
                1);
    }

    @Test
    void pageIsOnlyAllowedOnAReadOnlyQuery() {
        assertRejected(BASE.replace("expose query", "expose command"), "SIR-VALID-001", "expose command", 1);
    }

    @Test
    void orderFieldMustBeADeclaredFieldOfTheRootEntity() {
        assertRejected(
                BASE.replace("order by code ascending, id ascending", "order by missing ascending"),
                "SIR-SYMBOL-002",
                "missing ascending",
                1);
    }

    @Test
    void orderFieldMustBeComparable() {
        assertRejected(
                BASE.replace(
                                "field capacity: Int32 where min(1);",
                                "field capacity: Int32 where min(1);\n  field level: CourseLevel;")
                        .replace("entity Course persistent {", "enum CourseLevel { BASIC, ADVANCED }\nentity Course persistent {")
                        .replace("order by code ascending", "order by level ascending"),
                "SIR-TYPE-001",
                "level ascending",
                1);
    }

    @Test
    void orderFieldsMustNotRepeat() {
        assertRejected(
                BASE.replace("order by code ascending, id ascending", "order by code ascending, code descending"),
                "SIR-VALID-001",
                "code descending",
                1);
    }

    @Test
    void pageSourcesMustBeInt32() {
        assertRejected(
                BASE.replace("field page: Int32;", "field page: Int64;"),
                "SIR-TYPE-001",
                "input.page, input.size",
                1);
    }

    @Test
    void pageAndSizeMustBeDifferentFields() {
        assertRejected(
                BASE.replace("Page input.page, input.size", "Page input.page, input.page"),
                "SIR-VALID-001",
                "input.page else",
                1);
    }

    @Test
    void pageErrorMustBeDeclaredInTheCapabilityFailsClause() {
        assertRejected(
                BASE.replace("fails InvalidPageParam;\n", "").replace("error InvalidPageParam;", "error OtherError;\nerror InvalidPageParam;"),
                "SIR-FLOW-003",
                "InvalidPageParam\n",
                1);
    }

    @Test
    void pagedFindRootMustBeTheViewSourceEntity() {
        assertRejected(
                BASE.replace(
                                "entity Course persistent {",
                                "entity Maker persistent {\n  identity id: Int64 generated auto;\n  field code: String;\n  field name: String;\n  field capacity: Int32;\n}\nentity Course persistent {")
                        .replace("view CourseSummary from Course", "view CourseSummary from Maker"),
                "SIR-VALID-001",
                "view CourseSummary from Maker",
                1);
    }

    @Test
    void pagedFindRequiresAPageOutput() {
        assertRejected(
                BASE.replace("output Page<CourseSummary>", "output List<CourseSummary>"),
                "SIR-VALID-001",
                "List<CourseSummary>",
                1);
    }

    @Test
    void containsLiteralRequiresAStringFieldOnTheLeftHandSide() {
        assertRejected(
                BASE.replace("item.name containsLiteral input.keyword", "item.capacity containsLiteral input.keyword"),
                "SIR-TYPE-001",
                "item.capacity containsLiteral",
                1);
    }

    @Test
    void containsLiteralCannotCompareTwoEntityFields() {
        assertRejected(
                BASE.replace("item.name containsLiteral input.keyword", "item.name containsLiteral item.code"),
                "SIR-VALID-001",
                "item.name containsLiteral item.code",
                1);
    }

    private static SemanticAnalysis analyze(String declarations) {
        return TestSources.analyze(TestSources.sir(declarations));
    }

    private static void assertRejected(String declarations, String code, String needle, int expectedCount) {
        SemanticAnalysis result = analyze(declarations);
        assertFalse(result.isSuccess(), () -> "expected rejection but analysis succeeded: " + code);

        List<Diagnostic> matching = result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().value().equals(code) && diagnostic.isError())
                .toList();
        assertEquals(expectedCount, matching.size(), () -> "expected exactly " + expectedCount + " " + code
                + " but got " + TestSources.errorCodes(result));
        assertEquals(
                codePointOffset(TestSources.sir(declarations), needle),
                matching.getFirst().primarySpan().start().codePointOffset(),
                () -> "diagnostic " + code + " must point at '" + needle + "'");
    }

    private static int codePointOffset(String source, String needle) {
        int charIndex = source.indexOf(needle);
        assertTrue(charIndex >= 0, "needle not found in source: " + needle);
        return source.codePointCount(0, charIndex);
    }

    private static NormalizedView view(NormalizedSemanticModel model) {
        return model.declarations().stream()
                .filter(NormalizedView.class::isInstance)
                .map(NormalizedView.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedStep.FindStep pagedFind(NormalizedSemanticModel model) {
        return capability(model, "SearchCourses").workflow().steps().stream()
                .filter(NormalizedStep.FindStep.class::isInstance)
                .map(NormalizedStep.FindStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SymbolId entitySymbol(NormalizedSemanticModel model, String name) {
        return model.symbols().all().stream()
                .filter(symbol -> symbol.name().equals(name))
                .filter(symbol -> symbol.id().value().contains("/entity/" + name))
                .map(Symbol::id)
                .findFirst()
                .orElseThrow();
    }
}
