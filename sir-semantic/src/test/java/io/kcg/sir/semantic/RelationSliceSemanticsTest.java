package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSiteBinding;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.model.NormalizedViewField;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.type.PrimitiveType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The relation slice's semantic rules: the existence predicate {@code any(<Entity>, <conditions>)}
 * and the nested projection field.
 *
 * <p>Both sides are asserted. The legal source analyzes clean and produces the normalized shapes
 * the target needs — the existence predicate carries the entity, the connection field and the
 * conditions; a nested projection carries the relation field, its cardinality and the target view.
 * Every illegal combination is rejected by its own phase with its own code, its own count and its
 * own span, so a source that trips several rules cannot pass as one.
 */
class RelationSliceSemanticsTest {
    private static final String FIXTURE = "valid/course-enrollment.sir";

    private static final String ENTITIES = """
            enum EnrollmentStatus {
              ACTIVE,
              CANCELLED
            }
            entity Student persistent {
              identity id: Int64 generated auto;
              field studentNo: String where notBlank, length(1, 16);
              field name: String where notBlank, length(1, 100);
            }
            entity Course persistent {
              identity id: Int64 generated auto;
              field code: String where notBlank, length(1, 32);
              field name: String where notBlank, length(1, 100);
              field capacity: Int32 where min(1);
            }
            entity Enrollment persistent {
              identity id: Int64 generated auto;
              field course: Ref<Course>;
              field student: Ref<Student>;
              field status: EnrollmentStatus;
            }
            """;

    private static final String VIEWS = """
            view StudentSummary from Student {
              field id: Int64;
              field studentNo: String;
              field name: String;
            }
            view EnrollmentSummary from Enrollment {
              field id: Int64;
              field student: StudentSummary;
              field status: EnrollmentStatus;
            }
            view CourseEnrollmentItem from Course {
              field id: Int64;
              field code: String;
              field name: String;
              field capacity: Int32;
              field enrollments: List<EnrollmentSummary>;
            }
            """;

    private static final String SEARCH_INPUT = """
            input SearchCourseEnrollmentsInput {
              field page: Int32;
              field size: Int32;
            }
            error InvalidPage;
            """;

    @Test
    void theRelationSliceSourceAnalyzesWithoutDiagnostics() {
        SemanticAnalysis analysis = TestSources.analyzeResource(FIXTURE);

        assertTrue(analysis.isSuccess(), () -> TestSources.errorCodes(analysis).toString());
        assertTrue(analysis.diagnostics().isEmpty(), () -> messages(analysis));
    }

    @Test
    void theExistencePredicateCarriesItsEntityItsConnectionFieldAndTheConditions() {
        NormalizedSemanticModel model = TestSources.analyzeResource(FIXTURE).model().orElseThrow();

        NormalizedExpression.ExistsExpression exists = assertInstanceOf(
                NormalizedExpression.ExistsExpression.class,
                findStep(model, "SearchCourseEnrollments").predicate(),
                "find 的存在性条件必须规范化成专门的节点");
        assertEquals(PrimitiveType.BOOLEAN, exists.type(), "存在性谓词的类型是 Boolean");

        SymbolId sourceEntity = bindingTarget(model, ReferenceRole.EXISTS_SOURCE_ENTITY, "Enrollment");
        assertEquals(sourceEntity, exists.entity(), "第一个参数绑定到被引用实体");
        assertEquals(SymbolKind.ENTITY, kindOf(model, sourceEntity));

        SymbolId connectionField = bindingTarget(model, ReferenceRole.EXISTS_CONDITION_FIELD, "course");
        assertEquals(connectionField, exists.connectionField(), "连接字段是规范模型中显式记录的字段");
        Symbol.FieldSymbol field = assertInstanceOf(
                Symbol.FieldSymbol.class, model.symbols().byId(connectionField).orElseThrow());
        assertEquals(sourceEntity, field.ownerId(), "连接字段属于被引用的实体");

        NormalizedExpression.BinaryExpression conditions = assertInstanceOf(
                NormalizedExpression.BinaryExpression.class, exists.conditions());
        NormalizedExpression.BinaryExpression comparison = assertInstanceOf(
                NormalizedExpression.BinaryExpression.class, conditions.left());
        NormalizedExpression.NameExpression item =
                assertInstanceOf(NormalizedExpression.NameExpression.class, comparison.right());
        assertEquals("item", item.name());
        assertTrue(
                model.findItemBindings().containsValue(item.resolvedSymbol()),
                "连接条件的另一侧必须是 find 的 item，而不是其它同类变量");
    }

    @Test
    void aNestedProjectionRecordsItsRelationFieldItsCardinalityAndItsTargetView() {
        NormalizedSemanticModel model = TestSources.analyzeResource(FIXTURE).model().orElseThrow();
        SymbolId enrollmentSummary = viewId(model, "EnrollmentSummary");
        SymbolId studentSummary = viewId(model, "StudentSummary");

        NormalizedViewField toMany = viewField(model, "CourseEnrollmentItem", "enrollments");
        NormalizedViewField.Relation collection = toMany.relation().orElseThrow();
        assertEquals(NormalizedViewField.Cardinality.TO_MANY, collection.cardinality());
        assertEquals(enrollmentSummary, collection.targetView());
        assertEquals(
                bindingTarget(model, ReferenceRole.VIEW_RELATION_FIELD, "enrollments"),
                toMany.sourceField(),
                "对多投影读的是子实体侧的外键字段");

        NormalizedViewField toOne = viewField(model, "EnrollmentSummary", "student");
        NormalizedViewField.Relation single = toOne.relation().orElseThrow();
        assertEquals(NormalizedViewField.Cardinality.TO_ONE, single.cardinality());
        assertEquals(studentSummary, single.targetView());
        assertEquals(
                bindingTarget(model, ReferenceRole.VIEW_RELATION_FIELD, "student"),
                toOne.sourceField(),
                "对一投影读的是本实体侧的外键字段");
    }

    @Test
    void theExistencePredicatesSourceMustBeAnEntity() {
        assertRejectedOnce(catalogSource("any(EnrollmentStatus, course == item)"), "SIR-TYPE-001", "EnrollmentStatus");
    }

    @Test
    void theExistencePredicatesSourceMustBeDeclared() {
        assertRejectedOnce(catalogSource("any(Ghost, course == item)"), "SIR-SYMBOL-002", "Ghost");
    }

    @Test
    void theExistencePredicatesConditionMustBeBoolean() {
        assertRejectedOnce(catalogSource("any(Enrollment, course)"), "SIR-TYPE-001", "course");
    }

    @Test
    void theExistencePredicateMustCarryTheConnectionToTheRoot() {
        assertRejectedOnce(
                catalogSource("any(Enrollment, status == EnrollmentStatus.ACTIVE)"),
                "SIR-TYPE-004",
                "any(Enrollment, status == EnrollmentStatus.ACTIVE)");
    }

    @Test
    void theConnectionMustCompareAgainstTheItemAndNotAnotherSameTypedVariable() {
        String source = document(ENTITIES
                + VIEWS
                + SEARCH_INPUT
                + """
                capability SearchCourseEnrollments {
                  actor Ref<Course>;
                  input SearchCourseEnrollmentsInput;
                  output Page<CourseEnrollmentItem>;
                  fails InvalidPage;
                  requires readonly;
                  expose query;
                  workflow {
                    find Course
                      where any(Enrollment, course == actor)
                      Page input.page, input.size else InvalidPage
                    as courses;
                    return courses;
                  }
                }
                """);

        assertRejectedOnce(source, "SIR-TYPE-004", "any(Enrollment, course == actor)");
    }

    @Test
    void theExistencePredicateIsRejectedOutsideAFindPredicate() {
        String source = document(ENTITIES
                + VIEWS
                + SEARCH_INPUT
                + """
                capability GuardedQuery {
                  output Page<CourseEnrollmentItem>;
                  fails InvalidPage;
                  requires readonly;
                  expose query;
                  workflow {
                    validate any(Enrollment, course == item) else InvalidPage;
                    find Course where item.code == item.code Page input.page, input.size else InvalidPage as courses;
                    return courses;
                  }
                }
                """);

        assertRejectedOnce(source, "SIR-FLOW-005", "any(Enrollment, course == item)");
    }

    @Test
    void theExistencePredicateIsRejectedInsideACreateBinding() {
        String source = document(ENTITIES
                + """
                input SeedCourseInput {
                  field name: String;
                  field capacity: Int32;
                }
                capability SeedCourse {
                  input SeedCourseInput;
                  output Ref<Course>;
                  requires atomic;
                  expose command;
                  workflow {
                    create Course as created {
                      name: input.name;
                      capacity: input.capacity;
                      code: any(Enrollment, course == item);
                    }
                    persist created;
                    return created;
                  }
                }
                """);

        assertRejectedOnce(source, "SIR-FLOW-005", "any(Enrollment, course == item)");
    }

    @Test
    void theExistencePredicateCannotNestAnotherExistencePredicate() {
        assertRejectedOnce(
                catalogSource("any(Enrollment, course == item and any(Enrollment, course == item))"),
                "SIR-FLOW-006",
                "any(Enrollment, course == item)");
    }

    @Test
    void aNestedProjectionWithoutARelationFieldIsRejected() {
        String source = document(ENTITIES
                + """
                view StudentSummary from Student {
                  field id: Int64;
                  field name: String;
                }
                view CourseStudentList from Course {
                  field id: Int64;
                  field students: List<StudentSummary>;
                }
                """);

        assertRejectedOnce(source, "SIR-SYMBOL-002", "students");
    }

    @Test
    void aNestedProjectionWithTwoCandidateRelationFieldsIsRejected() {
        String source = document(
                ENTITIES.replace(
                        "field course: Ref<Course>;",
                        "field course: Ref<Course>;\n  field waitCourse: Ref<Course>;")
                        + VIEWS);

        assertRejectedOnce(source, "SIR-SYMBOL-003", "enrollments");
    }

    @Test
    void aNestedProjectionDeeperThanTwoLevelsIsRejected() {
        String source = document(ENTITIES
                + """
                entity Grade persistent {
                  identity id: Int64 generated auto;
                  field enrollment: Ref<Enrollment>;
                  field score: Int32;
                }
                view GradeSummary from Grade {
                  field id: Int64;
                  field score: Int32;
                }
                view StudentSummary from Student {
                  field id: Int64;
                  field grades: List<GradeSummary>;
                }
                view EnrollmentSummary from Enrollment {
                  field id: Int64;
                  field student: StudentSummary;
                }
                view CourseEnrollmentItem from Course {
                  field id: Int64;
                  field enrollments: List<EnrollmentSummary>;
                }
                """);

        assertRejectedOnce(source, "SIR-VALID-005", "enrollments");
    }

    @Test
    void aCollectionOfEntitiesIsNotAProjection() {
        String source = document(ENTITIES
                + """
                view CourseEnrollmentList from Course {
                  field id: Int64;
                  field enrollments: List<Enrollment>;
                }
                """);

        // The existing type rule rejects an entity element twice — once at the element and once at
        // the list that wraps it — so this case asserts both texts instead of the usual single one.
        SemanticAnalysis analysis = TestSources.analyze(source);

        assertEquals(
                List.of("Enrollment", "List<Enrollment>"),
                coveredTexts(source, analysis, "SIR-TYPE-001"),
                () -> messages(analysis));
    }

    @Test
    void aNestedProjectionFieldCannotDeclareConstraints() {
        String source = document(ENTITIES
                + VIEWS.replace("field student: StudentSummary;", "field student: StudentSummary where notBlank;"));

        assertRejectedOnce(source, "SIR-VALID-001", "notBlank");
    }

    @Test
    void aToOneProjectionWithoutTheRelationOnThisSideIsRejected() {
        String source = document(ENTITIES
                + """
                view StudentSummary from Student {
                  field id: Int64;
                  field name: String;
                }
                view CourseStudent from Course {
                  field id: Int64;
                  field student: StudentSummary;
                }
                """);

        assertRejectedOnce(source, "SIR-SYMBOL-002", "student");
    }

    private static String catalogSource(String predicate) {
        return document(ENTITIES
                + VIEWS
                + SEARCH_INPUT
                + """
                capability SearchCourseEnrollments {
                  input SearchCourseEnrollmentsInput;
                  output Page<CourseEnrollmentItem>;
                  fails InvalidPage;
                  requires readonly;
                  expose query;
                  workflow {
                    find Course
                      where %s
                      Page input.page, input.size else InvalidPage
                    as courses;
                    return courses;
                  }
                }
                """.formatted(predicate));
    }

    private static NormalizedStep.FindStep findStep(NormalizedSemanticModel model, String capabilityName) {
        for (NormalizedStep step : capability(model, capabilityName).workflow().steps()) {
            if (step instanceof NormalizedStep.FindStep find) {
                return find;
            }
        }

        throw new IllegalStateException("no find step in " + capabilityName);
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedCapability.class::isInstance)
                .map(NormalizedCapability.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedView view(NormalizedSemanticModel model, String name) {
        return model.declarations().stream()
                .filter(NormalizedView.class::isInstance)
                .map(NormalizedView.class::cast)
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static NormalizedViewField viewField(NormalizedSemanticModel model, String viewName, String fieldName) {
        return view(model, viewName).fields().stream()
                .filter(value -> value.name().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    private static SymbolId viewId(NormalizedSemanticModel model, String name) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedView view && view.name().equals(name)) {
                return view.id();
            }
        }

        throw new IllegalStateException("no view: " + name);
    }

    private static SymbolId bindingTarget(NormalizedSemanticModel model, ReferenceRole role, String text) {
        return model.referenceSiteBindings().all().stream()
                .filter(binding -> binding.site().role() == role)
                .filter(binding -> binding.site().text().equals(text))
                .map(ReferenceSiteBinding::targetSymbol)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + role + " binding for " + text));
    }

    private static SymbolKind kindOf(NormalizedSemanticModel model, SymbolId id) {
        return model.symbols().byId(id).orElseThrow().kind();
    }

    /**
    * Asserts that exactly one diagnostic carries {@code code}, and that the text it covers is
    * exactly {@code covered}. Counting per code keeps a source that trips several rules from
    * passing as one; comparing the covered text pins the span without depending on where else the
    * same words appear in the document.
    */
    private void assertRejectedOnce(String source, String code, String covered) {
        SemanticAnalysis analysis = TestSources.analyze(source);
        List<Diagnostic> matching = analysis.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().value().equals(code))
                .toList();

        assertEquals(1, matching.size(), () -> "expected one " + code + ", got " + messages(analysis));
        Diagnostic diagnostic = matching.getFirst();
        int start = diagnostic.primarySpan().start().codePointOffset();
        int end = diagnostic.primarySpan().end().codePointOffset();
        int[] codePoints = source.codePoints().toArray();

        assertEquals(
                covered,
                new String(codePoints, start, end - start),
                () -> code + " should cover the offending text: " + messages(analysis));
    }

    /** The source text covered by every diagnostic that carries {@code code}, in diagnostic order. */
    private static List<String> coveredTexts(String source, SemanticAnalysis analysis, String code) {
        int[] codePoints = source.codePoints().toArray();
        return analysis.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().value().equals(code))
                .map(diagnostic -> new String(
                        codePoints,
                        diagnostic.primarySpan().start().codePointOffset(),
                        diagnostic.primarySpan().end().codePointOffset()
                                - diagnostic.primarySpan().start().codePointOffset()))
                .sorted()
                .toList();
    }

    private static String document(String declarations) {
        return """
                sir 0.1
                software CourseEnrollment {
                  metadata {
                    displayName "Course Enrollment";
                    namespace "com.example.courseenrollment";
                  }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
                  declarations {
                    %s
                  }
                }
                """.formatted(declarations);
    }

    private static String messages(SemanticAnalysis analysis) {
        return analysis.diagnostics().stream()
                .map(value -> value.code().value() + ": " + value.message())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }
}
