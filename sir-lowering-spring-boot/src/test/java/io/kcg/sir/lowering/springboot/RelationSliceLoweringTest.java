package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Q11 Lowered IR contract for relations: the existence predicate's correlated subquery, the batch
 * read a nested projection needs, and the read budget a find promises.
 *
 * <p>Every negative case damages exactly one fact of an otherwise valid model and requires the IR
 * validator to reject it, so the rules are shown to be load-bearing rather than decorative. The
 * existence predicate's SQL is asserted verbatim: the correlated form is a target decision, and a
 * change to it is a change of contract, not a refactor.
 */
class RelationSliceLoweringTest {

    private static final String SOURCE = "valid/course-enrollment.sir";

    @Test
    void theExistencePredicateLowersToACorrelatedSubqueryOnTheReferencedField() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringExpression.ExistsPredicate exists = assertInstanceOf(
                SpringExpression.ExistsPredicate.class,
                pagedFind(model).predicate(),
                "find 的存在性条件必须降低成相关子查询");
        assertEquals(
                "SELECT 1 FROM enrollment enrollment_rel WHERE enrollment_rel.course_id = course.id"
                        + " AND enrollment_rel.status = {0}",
                exists.subquerySql(),
                "连接比较成为关联本身，其余条件作为子查询的条件");
        assertEquals(
                List.of(new SpringExpression.ExistsArgument.Text("ACTIVE")),
                exists.arguments(),
                "枚举成员以持久化文本作为绑定值，不进入 SQL 文本");
        assertEquals(
                new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BOOLEAN),
                exists.type(),
                "存在性谓词的类型是 Boolean");
    }

    @Test
    void aCollectionProjectionCarriesTheBatchReadThatKeysOnTheRelatedReference() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootDeclaration.ViewRelationPlan plan = relationPlan(model, "CourseEnrollmentItem", "enrollments");
        assertEquals(SpringBootDeclaration.RelationCardinality.TO_MANY, plan.cardinality());
        assertEquals("Enrollment", entityName(model, plan.targetEntitySymbol()), "集合读取读的是子实体的行");
        assertEquals("id", plan.sourceKeyPropertyName(), "集合读取用投影实体的 identity 作为键");
        assertEquals("courseId", plan.targetLookupPropertyName(), "集合读取按子实体持有的引用属性过滤");
        assertEquals("courseId", plan.indexKeyPropertyName(), "读回的行按同一个引用分组");
        assertEquals(Optional.of("id"), plan.orderPropertyName(), "集合按 identity 升序，保证同页同序");
        assertEquals("Enrollment", viewEntityName(model, plan.targetViewSymbol()));
    }

    @Test
    void aSingleRowProjectionCarriesTheBatchReadThatKeysOnItsOwnReference() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootDeclaration.ViewRelationPlan plan = relationPlan(model, "EnrollmentSummary", "student");
        assertEquals(SpringBootDeclaration.RelationCardinality.TO_ONE, plan.cardinality());
        assertEquals("id", plan.targetLookupPropertyName(), "对一读取按被引用实体的 identity 过滤");
        assertEquals("studentId", plan.sourceKeyPropertyName(), "对一读取从本实体的外键属性取值");
        assertEquals(Optional.empty(), plan.orderPropertyName(), "对一读取不需要排序");
    }

    @Test
    void aPagedFindWithAssociationsDeclaresTheStatementBudgetItPromises() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.StatementBudget budget = pagedFind(model).statementBudget();
        assertEquals(2, budget.pageStatements(), "分页查询需要 count 与分页两条语句");
        assertEquals(2, budget.associationStatements(), "两层关联投影各需一条批量读取");
        assertEquals(4, budget.total());
        assertEquals(2, budget.emptyPageStatements(), "空页仍要执行分页读取，但跳过批量读取");
    }

    @Test
    void theRelationSliceModelPassesTheIrValidator() {
        assertEquals(
                List.of(),
                new SpringBootLoweredIrValidator().validate(LoweringTestSupport.lowerSuccess(SOURCE)),
                "关系切片的降低结果必须自洽");
    }

    @Test
    void aBudgetThatDoesNotCountTheAssociationsIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> new SpringBootWorkflow.FindStep(
                        find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(), find.page(),
                        find.stringMatches(), new SpringBootWorkflow.StatementBudget(2, 1), find.result(), find.itemVariable())),
                "the response projection nests 2 batch reads but the budget says 1");
    }

    @Test
    void aPagedFindWhoseBudgetClaimsOnePageReadIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> new SpringBootWorkflow.FindStep(
                        find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(), find.page(),
                        find.stringMatches(), new SpringBootWorkflow.StatementBudget(1, 2), find.result(), find.itemVariable())),
                "the page reads of a find must be 2 but the budget says 1");
    }

    @Test
    void aRelationPlanThatIndexesByAnotherPropertyIsRejected() {
        assertRejected(
                withViewField(LoweringTestSupport.lowerSuccess(SOURCE), "CourseEnrollmentItem", "enrollments", field -> {
                    SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElseThrow();
                    return new SpringBootDeclaration.ViewField(
                            field.id(), field.origin(), field.sourceSymbol(), field.javaName(), field.type(),
                            field.sourceFieldSymbol(), field.sourcePropertyName(),
                            Optional.of(new SpringBootDeclaration.ViewRelationPlan(
                                    plan.id(), plan.origin(), plan.viewFieldSymbol(), plan.cardinality(),
                                    plan.targetViewSymbol(), plan.targetEntitySymbol(), plan.sourceKeyPropertyName(),
                                    plan.targetLookupPropertyName(), "code", plan.orderPropertyName())));
                }),
                "a relation is indexed by the property it was read with");
    }

    @Test
    void aCollectionRelationWithoutAnOrderIsRejectedAtConstruction() {
        // The order belongs to the plan's own invariant, so nothing downstream can observe a
        // collection relation without one; the IR validator re-checks it for decoded models.
        SpringBootDeclaration.ViewRelationPlan plan = relationPlan(
                LoweringTestSupport.lowerSuccess(SOURCE), "CourseEnrollmentItem", "enrollments");

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new SpringBootDeclaration.ViewRelationPlan(
                        plan.id(), plan.origin(), plan.viewFieldSymbol(), plan.cardinality(),
                        plan.targetViewSymbol(), plan.targetEntitySymbol(), plan.sourceKeyPropertyName(),
                        plan.targetLookupPropertyName(), plan.indexKeyPropertyName(), Optional.empty()));
        assertTrue(thrown.getMessage().contains("must state the order"), () -> thrown.getMessage());
    }

    @Test
    void aCorrelatedSubqueryWithoutAValueForItsPlaceholderIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> new SpringBootWorkflow.FindStep(
                        find.id(), find.origin(), find.entitySymbol(),
                        rewriteExists(find.predicate(), exists -> new SpringExpression.ExistsPredicate(
                                exists.id(), exists.origin(), exists.type(), exists.subquerySql(), List.of())),
                        find.orderKeys(), find.page(), find.stringMatches(), find.statementBudget(),
                        find.result(), find.itemVariable())),
                "a correlated subquery must bind exactly the values it declares");
    }

    @Test
    void anExistenceConditionTheTargetCannotExpressIsRejectedBeforeLowering() {
        String source = LoweringTestSupport.source(SOURCE)
                .replace("field size: Int32;", "field size: Int32;\n      field status: EnrollmentStatus;")
                .replace("status == EnrollmentStatus.ACTIVE", "status == input.status");

        List<LoweringDiagnostic> diagnostics = LoweringTestSupport.inputDiagnostics(source);
        List<LoweringDiagnostic> matching = diagnostics.stream()
                .filter(diagnostic -> "SIR-LOWER-FEATURE-001".equals(diagnostic.code().value()))
                .toList();

        assertEquals(1, matching.size(), () -> diagnostics.toString());
        assertTrue(
                matching.getFirst().message().contains("cannot express the existence condition"),
                () -> matching.getFirst().message());
    }

    private static SpringExpression rewriteExists(
            SpringExpression expression, UnaryOperator<SpringExpression.ExistsPredicate> change) {
        return switch (expression) {
            case SpringExpression.ExistsPredicate exists -> change.apply(exists);
            case SpringExpression.BinaryExpression binary -> new SpringExpression.BinaryExpression(
                    binary.id(), binary.origin(), binary.type(),
                    rewriteExists(binary.left(), change), binary.operator(), rewriteExists(binary.right(), change));
            case SpringExpression.UnaryExpression unary -> new SpringExpression.UnaryExpression(
                    unary.id(), unary.origin(), unary.type(), unary.operator(), rewriteExists(unary.operand(), change));
            default -> expression;
        };
    }

    private static SpringBootWorkflow.FindStep pagedFind(SpringBootLoweredModel model) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.FindStep.class::isInstance)
                .map(SpringBootWorkflow.FindStep.class::cast)
                .filter(find -> find.page().isPresent())
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.ViewRelationPlan relationPlan(
            SpringBootLoweredModel model, String viewName, String fieldName) {
        return viewField(model, viewName, fieldName).relation().orElseThrow();
    }

    private static SpringBootDeclaration.ViewField viewField(
            SpringBootLoweredModel model, String viewName, String fieldName) {
        return view(model, viewName).fields().stream()
                .filter(field -> field.javaName().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.ViewDeclaration view(SpringBootLoweredModel model, String viewName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ViewDeclaration.class::cast)
                .filter(view -> view.javaName().equals(viewName))
                .findFirst()
                .orElseThrow();
    }

    private static String entityName(SpringBootLoweredModel model, io.kcg.sir.semantic.symbol.SymbolId symbol) {
        return ((SpringBootDeclaration.EntityDeclaration) model.declarations().stream()
                .filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .filter(entity -> entity.sourceSymbol().equals(symbol))
                .findFirst()
                .orElseThrow()).javaName();
    }

    private static String viewEntityName(SpringBootLoweredModel model, io.kcg.sir.semantic.symbol.SymbolId symbol) {
        return entityName(model, view(model, viewNameOf(model, symbol)).sourceEntitySymbol());
    }

    private static String viewNameOf(SpringBootLoweredModel model, io.kcg.sir.semantic.symbol.SymbolId symbol) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ViewDeclaration.class::cast)
                .filter(view -> view.sourceSymbol().equals(symbol))
                .findFirst()
                .orElseThrow()
                .javaName();
    }

    @Test
    void aRelationPlanThatReadsAnotherViewThanItProjectsIsRejected() {
        assertRejected(
                withViewField(LoweringTestSupport.lowerSuccess(SOURCE), "CourseEnrollmentItem", "enrollments", field -> {
                    SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElseThrow();
                    SymbolId studentSummary = viewSymbol(LoweringTestSupport.lowerSuccess(SOURCE), "StudentSummary");
                    return new SpringBootDeclaration.ViewField(
                            field.id(), field.origin(), field.sourceSymbol(), field.javaName(), field.type(),
                            field.sourceFieldSymbol(), field.sourcePropertyName(),
                            Optional.of(new SpringBootDeclaration.ViewRelationPlan(
                                    plan.id(), plan.origin(), plan.viewFieldSymbol(), plan.cardinality(),
                                    studentSummary, plan.targetEntitySymbol(), plan.sourceKeyPropertyName(),
                                    plan.targetLookupPropertyName(), plan.indexKeyPropertyName(), plan.orderPropertyName())));
                }),
                "a relation must project the view its plan reads");
    }

    @Test
    void aFindWithoutAssociationsPromisesOnlyItsPageReads() {
        // Projecting scalars and foreign keys needs no second read: the page itself carries them, so
        // the budget of a find without nested projections is exactly the count and the page.
        SpringBootWorkflow.StatementBudget budget = LoweringTestSupport.lowerSuccess("valid/course-catalog.sir")
                .declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.FindStep.class::isInstance)
                .map(SpringBootWorkflow.FindStep.class::cast)
                .filter(find -> find.page().isPresent())
                .findFirst()
                .orElseThrow()
                .statementBudget();

        assertEquals(2, budget.pageStatements());
        assertEquals(0, budget.associationStatements());
        assertEquals(2, budget.total());
    }

    private static SymbolId viewSymbol(SpringBootLoweredModel model, String javaName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ViewDeclaration.class::cast)
                .filter(view -> view.javaName().equals(javaName))
                .findFirst()
                .orElseThrow()
                .sourceSymbol();
    }

    private static List<LoweringDiagnostic> validate(SpringBootLoweredModel model) {
        return new SpringBootLoweredIrValidator().validate(model);
    }

    private static void assertRejected(SpringBootLoweredModel model, String messageFragment) {
        List<LoweringDiagnostic> diagnostics = validate(model);
        List<LoweringDiagnostic> matching = diagnostics.stream()
                .filter(diagnostic -> diagnostic.message().contains(messageFragment))
                .toList();
        assertEquals(
                1,
                matching.size(),
                () -> "expected exactly one diagnostic containing '" + messageFragment + "' but got " + diagnostics);
        assertEquals("SIR-LOWER-IR-001", matching.getFirst().code().value());
    }

    private static SpringBootLoweredModel withFindStep(
            SpringBootLoweredModel model, UnaryOperator<SpringBootWorkflow.FindStep> change) {
        return mapDeclarations(model, declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                ? withWorkflow(capability, capability.workflow().steps().stream()
                        .map(step -> step instanceof SpringBootWorkflow.FindStep find ? (SpringBootWorkflow.Step) change.apply(find) : step)
                        .toList())
                : declaration);
    }

    private static SpringBootLoweredModel withViewField(
            SpringBootLoweredModel model, String viewName, String fieldName,
            UnaryOperator<SpringBootDeclaration.ViewField> change) {
        return mapDeclarations(model, declaration -> declaration instanceof SpringBootDeclaration.ViewDeclaration view
                && view.javaName().equals(viewName)
                ? new SpringBootDeclaration.ViewDeclaration(
                        view.id(), view.origin(), view.sourceSymbol(), view.javaName(), view.sourceEntitySymbol(),
                        view.sourceEntityJavaName(), view.fields().stream()
                                .map(field -> field.javaName().equals(fieldName) ? change.apply(field) : field)
                                .toList())
                : declaration);
    }

    private static SpringBootDeclaration.CapabilityDeclaration withWorkflow(
            SpringBootDeclaration.CapabilityDeclaration capability, List<SpringBootWorkflow.Step> steps) {
        return new SpringBootDeclaration.CapabilityDeclaration(
                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                capability.serviceName(), capability.controllerName(), capability.methodName(), capability.httpMethod(),
                capability.route(), capability.kind(), capability.transactionMode(), capability.authenticated(),
                capability.actor(), capability.input(), capability.actorBinding(), capability.transportPlan(),
                capability.outputType(), capability.failures(),
                new SpringBootWorkflow(capability.workflow().id(), capability.workflow().origin(),
                        capability.workflow().variables(), steps));
    }

    private static SpringBootLoweredModel mapDeclarations(
            SpringBootLoweredModel model, UnaryOperator<SpringBootDeclaration> change) {
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                model.declarations().stream().map(change).toList(), model.artifacts(), model.mavenProject(),
                model.applicationMain(), model.projectArtifacts());
    }
}
