package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Q9 Lowered IR contract for the query slice, plus the failure modes of its validator.
 *
 * <p>Every negative case damages exactly one fact of an otherwise valid model and requires the IR
 * validator to reject it, so the rules are shown to be load-bearing rather than decorative.
 */
class QuerySliceLoweringTest {

    private static final String SOURCE = "valid/course-catalog.sir";

    @Test
    void viewLowersToAProjectionWithEntityFieldProvenance() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootDeclaration.ViewDeclaration view = view(model);

        assertEquals("CourseSummary", view.javaName());
        assertEquals("Course", view.sourceEntityJavaName());
        assertEquals(
                List.of("code", "name", "capacity"),
                view.fields().stream().map(SpringBootDeclaration.ViewField::javaName).toList());
        assertEquals(
                List.of(
                        new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.STRING),
                        new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.STRING),
                        new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.INTEGER)),
                view.fields().stream().map(SpringBootDeclaration.ViewField::type).toList());
        assertTrue(
                view.fields().stream().allMatch(field -> field.sourceFieldSymbol().value().contains("/entity/Course/field/")),
                () -> view.fields().toString());
    }

    @Test
    void viewIsOwnedByItsOwnDtoArtifact() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringArtifact artifact = model.artifacts().stream()
                .filter(candidate -> candidate.role() == SpringArtifact.Role.VIEW_DTO)
                .findFirst()
                .orElseThrow();
        assertEquals("com.example.coursecatalog.api.CourseSummary", artifact.qualifiedName());
    }

    @Test
    void pagedFindCarriesOrderKeysPaginationAndLiteralMatchPlan() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.FindStep find = pagedFind(model);

        assertEquals(2, find.orderKeys().size());
        assertEquals("code", find.orderKeys().get(0).targetMemberName());
        assertTrue(find.orderKeys().stream().noneMatch(SpringBootWorkflow.OrderKey::descending));

        SpringBootWorkflow.PageSpec page = find.page().orElseThrow();
        assertEquals("page", page.pagePropertyName());
        assertEquals("size", page.sizePropertyName());
        assertEquals(20, page.defaultSize());
        assertEquals(100, page.maxSize());
        assertEquals(10_000, page.maxPageNumber());
        assertEquals("InvalidPageParamException", errorName(model, page.errorSymbol()));

        assertEquals(1, find.stringMatches().size());
        SpringExpression.StringMatch match = find.stringMatches().getFirst();
        assertEquals('\\', match.escapeCharacter());
        assertEquals(List.of("\\", "%", "_"), match.escapedLiterals());
    }

    @Test
    void literalMatchPlanDescribesExactlyThePredicatesLiteralMatches() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.FindStep find = pagedFind(model);
        SpringExpression.BinaryExpression predicate = assertInstanceOf(
                SpringExpression.BinaryExpression.class, find.predicate());

        assertEquals(SpringExpression.BinaryOperator.CONTAINS_LITERAL, predicate.operator());
        assertEquals(
                List.of(predicate.id()),
                find.stringMatches().stream().map(SpringExpression.StringMatch::expressionId).toList());
    }

    @Test
    void paginationSupportAppearsAsAnEnvelopeFile() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        ProjectArtifact.PageResponse envelope = assertInstanceOf(
                ProjectArtifact.PageResponse.class, model.projectArtifacts().getFirst());
        assertEquals("src/main/java/com/example/coursecatalog/api/PageResponse.java", envelope.path());
        assertEquals("com.example.coursecatalog.api", envelope.packageName());
    }

    @Test
    void capabilityOutputIsAPageOfTheViewAndTheFindResultHoldsIt() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootDeclaration.CapabilityDeclaration capability = capability(model, "SearchCourses");

        LoweredJavaType.PageValue output = assertInstanceOf(LoweredJavaType.PageValue.class, capability.outputType());
        assertInstanceOf(LoweredJavaType.Declared.class, output.elementType());
        assertEquals(pagedFind(model).result().type(), capability.outputType());
    }

    @Test
    void aValidQuerySlicePassesItsOwnIrValidator() {
        assertEquals(List.of(), validate(LoweringTestSupport.lowerSuccess(SOURCE)));
    }

    @Test
    void predicateLiteralMatchWithoutAPlanEntryIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> new SpringBootWorkflow.FindStep(
                        find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(),
                        find.page(), List.of(), find.statementBudget(), find.result(), find.itemVariable())),
                "has no plan entry");
    }

    @Test
    void planEntryWithoutAPredicateNodeIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> new SpringBootWorkflow.FindStep(
                        find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(), find.page(),
                        List.of(new SpringExpression.StringMatch(
                                find.stringMatches().getFirst().id(), find.stringMatches().getFirst().origin(),
                                new io.kcg.sir.lowering.api.LoweredNodeId("lir://absent"), '\\', List.of("\\", "%", "_"))),
                        find.statementBudget(), find.result(), find.itemVariable())),
                "has no predicate node");
    }

    @Test
    void escapingThatContradictsTheQueryPolicyIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> new SpringBootWorkflow.FindStep(
                        find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(), find.page(),
                        List.of(new SpringExpression.StringMatch(
                                find.stringMatches().getFirst().id(), find.stringMatches().getFirst().origin(),
                                find.stringMatches().getFirst().expressionId(), '!', List.of("!", "%", "_"))),
                        find.statementBudget(), find.result(), find.itemVariable())),
                "escaping must match the target query policy");
    }

    @Test
    void pageBoundsThatContradictTheQueryPolicyAreRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> {
                    SpringBootWorkflow.PageSpec page = find.page().orElseThrow();
                    return new SpringBootWorkflow.FindStep(
                            find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(),
                            Optional.of(new SpringBootWorkflow.PageSpec(
                                    page.pageFieldSymbol(), page.sizeFieldSymbol(), page.pagePropertyName(),
                                    page.sizePropertyName(), 10, page.maxSize(), page.maxPageNumber(), page.errorSymbol())),
                            find.stringMatches(), find.statementBudget(), find.result(), find.itemVariable());
                }),
                "pagination bounds must match the target query policy");
    }

    @Test
    void pageNumberBeyondThePolicyMaximumIsRejected() {
        assertRejected(
                withFindStep(LoweringTestSupport.lowerSuccess(SOURCE), find -> {
                    SpringBootWorkflow.PageSpec page = find.page().orElseThrow();
                    return new SpringBootWorkflow.FindStep(
                            find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(),
                            Optional.of(new SpringBootWorkflow.PageSpec(
                                    page.pageFieldSymbol(), page.sizeFieldSymbol(), page.pagePropertyName(),
                                    page.sizePropertyName(), page.defaultSize(), page.maxSize(), 5_000, page.errorSymbol())),
                            find.stringMatches(), find.statementBudget(), find.result(), find.itemVariable());
                }),
                "pagination bounds must match the target query policy");
    }

    @Test
    void paginationWithoutTheEnvelopeFileIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootLoweredModel damaged = new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                model.declarations(), model.artifacts(), model.mavenProject(), model.applicationMain(), List.of());

        assertRejected(damaged, "exactly one PageResponse project artifact");
    }

    @Test
    void pageEnvelopeOutsideTheApiPackageIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        ProjectArtifact.PageResponse envelope = (ProjectArtifact.PageResponse) model.projectArtifacts().getFirst();
        SpringBootLoweredModel damaged = new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                model.declarations(), model.artifacts(), model.mavenProject(), model.applicationMain(),
                List.of(new ProjectArtifact.PageResponse(
                        envelope.id(), envelope.origin(), envelope.path(), model.basePackage(), envelope.simpleName())));

        assertRejected(damaged, "PageResponse packageName must be");
    }

    @Test
    void pageEnvelopeWithoutAPagedQueryIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootLoweredModel damaged = withFindStep(model, find -> new SpringBootWorkflow.FindStep(
                find.id(), find.origin(), find.entitySymbol(), find.predicate(), find.orderKeys(), Optional.empty(),
                find.stringMatches(), find.statementBudget(), find.result(), find.itemVariable()));

        assertRejected(damaged, "PageResponse is present but no capability declares a paged find");
    }

    @Test
    void viewFieldTypeThatDriftsFromTheEntityFieldIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.ViewDeclaration view
                        ? (SpringBootDeclaration) new SpringBootDeclaration.ViewDeclaration(
                                view.id(), view.origin(), view.sourceSymbol(), view.javaName(), view.sourceEntitySymbol(),
                                view.sourceEntityJavaName(),
                                List.of(new SpringBootDeclaration.ViewField(
                                        view.fields().getFirst().id(), view.fields().getFirst().origin(),
                                        view.fields().getFirst().sourceSymbol(), view.fields().getFirst().javaName(),
                                        new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG),
                                        view.fields().getFirst().sourceFieldSymbol(),
                                        view.fields().getFirst().sourcePropertyName())))
                        : declaration)
                .toList();
        SpringBootLoweredModel damaged = new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());

        assertRejected(damaged, "view field type must equal its source entity field type");
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
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        ? (SpringBootDeclaration) capabilityWithWorkflow(capability, capability.workflow().steps().stream()
                                .map(step -> step instanceof SpringBootWorkflow.FindStep find
                                        ? (SpringBootWorkflow.Step) change.apply(find)
                                        : step)
                                .toList())
                        : declaration)
                .toList();
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static SpringBootDeclaration.CapabilityDeclaration capabilityWithWorkflow(
            SpringBootDeclaration.CapabilityDeclaration capability, List<SpringBootWorkflow.Step> steps) {
        SpringBootWorkflow workflow = new SpringBootWorkflow(
                capability.workflow().id(), capability.workflow().origin(), capability.workflow().variables(), steps);
        return new SpringBootDeclaration.CapabilityDeclaration(
                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                capability.serviceName(), capability.controllerName(), capability.methodName(), capability.httpMethod(),
                capability.route(), capability.kind(), capability.transactionMode(), capability.authenticated(),
                capability.actor(), capability.input(), capability.actorBinding(), capability.transportPlan(),
                capability.outputType(), capability.failures(), workflow);
    }

    private static SpringBootDeclaration.ViewDeclaration view(SpringBootLoweredModel model) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ViewDeclaration.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootWorkflow.FindStep pagedFind(SpringBootLoweredModel model) {
        return capability(model, "SearchCourses").workflow().steps().stream()
                .filter(SpringBootWorkflow.FindStep.class::isInstance)
                .map(SpringBootWorkflow.FindStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static String errorName(SpringBootLoweredModel model, io.kcg.sir.semantic.symbol.SymbolId errorSymbol) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ErrorDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ErrorDeclaration.class::cast)
                .filter(candidate -> candidate.sourceSymbol().equals(errorSymbol))
                .map(SpringBootDeclaration.ErrorDeclaration::javaName)
                .findFirst()
                .orElseThrow();
    }
}
