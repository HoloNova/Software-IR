package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Q10 Lowered IR contract for the write slice, plus the failure modes of its validator.
 *
 * <p>The write slice is only trustworthy if the decision that makes it correct — bound against a
 * version, merged onto a candidate, checked before it is written — survives lowering as data rather
 * than as a template's private habit. Each negative case damages exactly one fact of an otherwise
 * valid model, so the validator is shown to be load-bearing.
 */
class WriteSliceLoweringTest {

    private static final String SOURCE = "valid/course-admin.sir";

    @Test
    void entityCarriesItsVersionFieldAndInitialValue() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootDeclaration.EntityDeclaration course = entity(model, "Course");

        SpringBootDeclaration.VersionSpec version = course.version().orElseThrow();
        assertEquals("version", version.javaName());
        assertEquals("version", version.columnName());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG), version.type());
        assertEquals(0L, version.initialValue());
        assertTrue(
                course.fields().stream().anyMatch(field -> field.sourceSymbol().equals(version.fieldSymbol())),
                "the version field must be one of the entity's own fields");
    }

    @Test
    void patchPayloadCarriesItsIdentityAndChangeSet() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootDeclaration.PatchSpec patch = patchSpec(model, "UpdateCourseInput");

        assertEquals("Course", patch.sourceEntityJavaName());
        assertEquals("id", patch.identityPropertyName());
        assertEquals("changes", patch.changesPropertyName());
        assertEquals("expectedVersion", patch.expectedVersionPropertyName());
        assertEquals(
                List.of("name", "description", "capacity"),
                patch.changes().stream().map(SpringBootDeclaration.PatchChange::payloadPropertyName).toList());
        assertEquals(
                List.of("name", "description", "capacity"),
                patch.changes().stream().map(SpringBootDeclaration.PatchChange::entityPropertyName).toList());
        assertEquals(
                List.of("name", "description", "capacity"),
                patch.changes().stream().map(SpringBootDeclaration.PatchChange::entityColumnName).toList());
    }

    @Test
    void aPlainPayloadCarriesNoPatchSpec() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        assertTrue(payload(model, "CreateCourseInput").patch().isEmpty());
        assertTrue(payload(model, "GetCourseInput").patch().isEmpty());
    }

    @Test
    void versionedChangeLowersToAConditionalUpdateOverThePayloadsChangeSet() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.ConditionalUpdate conditional = conditionalUpdate(model, "UpdateCourse");

        assertEquals("course", conditional.tableName());
        assertEquals("id", conditional.identityPropertyName());
        assertEquals("id", conditional.identityColumnName());
        assertEquals("version", conditional.versionPropertyName());
        assertEquals("version", conditional.versionColumnName());
        assertEquals(1L, conditional.versionIncrement());
        assertEquals(
                List.of("name", "description", "capacity"),
                conditional.assignments().stream().map(SpringBootWorkflow.ColumnAssignment::entityColumnName).toList());
        assertEquals(
                List.of("name", "description", "capacity"),
                conditional.assignments().stream().map(SpringBootWorkflow.ColumnAssignment::payloadPropertyName).toList());
    }

    @Test
    void theLoadThatPrecedesAConditionalUpdateLocksItsRow() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.LoadStep load = model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals("UpdateCourse"))
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.LoadStep.class::isInstance)
                .map(SpringBootWorkflow.LoadStep.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(load.forUpdate(), "a conditional update must read its version under a row lock");
    }

    @Test
    void aCapabilityThatOnlyReadsDoesNotLockItsRow() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.LoadStep load = model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals("GetCourse"))
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.LoadStep.class::isInstance)
                .map(SpringBootWorkflow.LoadStep.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(!load.forUpdate(), "a read-only capability must not lock");
    }

    @Test
    void theConditionalPersistCarriesItsFailureAndTheInsertDoesNot() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.PersistStep updatePersist = persistStep(model, "UpdateCourse");
        assertEquals(name(model, "StaleVersionException"), updatePersist.failure().orElseThrow().value());
        assertTrue(updatePersist.conditional().isPresent(), "the versioned persist performs the conditional update");

        SpringBootWorkflow.PersistStep insert = persistStep(model, "CreateCourse");
        assertTrue(insert.failure().isEmpty(), "an insert has no zero-row outcome to report");
        assertTrue(insert.conditional().isEmpty());
    }

    @Test
    void declaredFailuresCarryTheirStatus() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        assertEquals(SpringBootDeclaration.HttpStatus.NOT_FOUND, status(model, "CourseNotFoundException"));
        assertEquals(SpringBootDeclaration.HttpStatus.CONFLICT, status(model, "StaleVersionException"));
        assertEquals(SpringBootDeclaration.HttpStatus.BAD_REQUEST, status(model, "EmptyChangeException"));
    }

    @Test
    void presenceLowersToThePayloadPropertiesItTests() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.ValidateStep guard = model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals("UpdateCourse"))
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.ValidateStep.class::isInstance)
                .map(SpringBootWorkflow.ValidateStep.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(
                List.of("name", "description", "capacity"),
                presenceProperties(guard.condition()));
    }

    @Test
    void writeCapabilitiesAnswerWithTheDeclaredProjection() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        for (String capability : List.of("GetCourse", "CreateCourse", "UpdateCourse")) {
            assertEquals(
                    TransportPlan.ResponseRepresentation.PROJECTION,
                    transportPlan(model, capability).responseRepresentation(),
                    capability);
        }
    }

    @Test
    void thePatchCapabilityIsExposedAsPatchAndTheInsertAsPost() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        assertEquals(SpringBootDeclaration.HttpMethod.PATCH, httpMethod(model, "UpdateCourse"));
        assertEquals(SpringBootDeclaration.HttpMethod.POST, httpMethod(model, "CreateCourse"));
        assertEquals(SpringBootDeclaration.HttpMethod.GET, httpMethod(model, "GetCourse"));
    }

    @Test
    void writeSupportArtifactsAppearWithAWritableCapability() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        assertTrue(model.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiErrorResponse.class::isInstance));
        assertTrue(model.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiExceptionBase.class::isInstance));
        assertTrue(model.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiExceptionAdvice.class::isInstance));
        assertTrue(model.projectArtifacts().stream().anyMatch(ProjectArtifact.ValidationSupport.class::isInstance));
        ProjectArtifact.ApplicationConfig config = model.projectArtifacts().stream()
                .filter(ProjectArtifact.ApplicationConfig.class::isInstance)
                .map(ProjectArtifact.ApplicationConfig.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(config.rejectUnknownRequestProperties(), "the target must refuse unknown request properties");
    }

    @Test
    void theErrorContractAndItsConstraintHelpersAppearForEveryProject() {
        // One error contract for every generated project: a query capability can fail too, and a client
        // should not read two different shapes depending on what a project happens to declare.
        SpringBootLoweredModel queryOnly = LoweringTestSupport.lowerSuccess("valid/course-catalog.sir");
        assertTrue(queryOnly.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiErrorResponse.class::isInstance));
        assertTrue(queryOnly.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiExceptionBase.class::isInstance));
        assertTrue(queryOnly.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiExceptionAdvice.class::isInstance));
        assertTrue(queryOnly.projectArtifacts().stream().anyMatch(ProjectArtifact.ApplicationConfig.class::isInstance));

        // The supporting files are a function of the target, not of the capability mix: a candidate
        // that drops the last writer must not silently stop producing one of them.
        assertTrue(queryOnly.projectArtifacts().stream().anyMatch(ProjectArtifact.ValidationSupport.class::isInstance));
    }

    @Test
    void aValidWriteSlicePassesItsOwnIrValidator() {
        assertEquals(List.of(), validate(LoweringTestSupport.lowerSuccess(SOURCE)));
    }

    @Test
    void aConditionalUpdateThatAssignsSomethingElseIsRejected() {
        // The candidate merge, the SET list, and the request's change set are one fact: an assignment
        // that drifts from the payload means the request would write a column nobody asked for.
        assertRejected(
                withUpdateStep(LoweringTestSupport.lowerSuccess(SOURCE), "UpdateCourse", update -> new SpringBootWorkflow.UpdateStep(
                        update.id(), update.origin(), update.targetVariable(), update.bindings(),
                        update.conditional().map(conditional -> new SpringBootWorkflow.ConditionalUpdate(
                                conditional.entitySymbol(), conditional.tableName(), conditional.identityFieldSymbol(),
                                conditional.identityPropertyName(), conditional.identityColumnName(),
                                conditional.versionFieldSymbol(), conditional.versionPropertyName(),
                                conditional.versionColumnName(), conditional.versionIncrement(),
                                conditional.failure(), conditional.assignments().subList(0, 1))))),
                "must assign exactly the payload's change set");
    }

    @Test
    void aConditionalUpdateWithoutItsVersionBoundsIsRejected() {
        assertRejected(
                withUpdateStep(LoweringTestSupport.lowerSuccess(SOURCE), "UpdateCourse", update -> new SpringBootWorkflow.UpdateStep(
                        update.id(), update.origin(), update.targetVariable(), update.bindings(),
                        update.conditional().map(conditional -> new SpringBootWorkflow.ConditionalUpdate(
                                conditional.entitySymbol(), conditional.tableName(), conditional.identityFieldSymbol(),
                                conditional.identityPropertyName(), conditional.identityColumnName(),
                                conditional.versionFieldSymbol(), conditional.versionPropertyName(), "revision",
                                conditional.versionIncrement(), conditional.failure(), conditional.assignments())))),
                "must bound on its own entity's version column");
    }

    @Test
    void aConditionalUpdateWithoutItsPayloadIsRejected() {
        // The payload is what a patch capability promises; without one, the conditional update would
        // have no request shape to apply.
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals("UpdateCourse")
                        ? (SpringBootDeclaration) new SpringBootDeclaration.CapabilityDeclaration(
                                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                                capability.serviceName(), capability.controllerName(), capability.methodName(),
                                SpringBootDeclaration.HttpMethod.POST, capability.route(), capability.kind(),
                                capability.transactionMode(), capability.authenticated(), capability.actor(),
                                Optional.empty(), capability.actorBinding(), capability.transportPlan(),
                                capability.outputType(), capability.failures(), capability.workflow())
                        : declaration)
                .toList();

        assertRejected(
                replace(model, declarations),
                "a conditional update requires its entity's patch payload",
                "a presence test requires its entity's patch payload");
    }

    @Test
    void aLoadForUpdateOutsideAConditionalUpdateIsRejected() {
        assertRejected(
                withLoadStep(LoweringTestSupport.lowerSuccess(SOURCE), "GetCourse", load -> new SpringBootWorkflow.LoadStep(
                        load.id(), load.origin(), load.entitySymbol(), load.idExpression(), load.result(),
                        load.errorSymbol(), true)),
                "a load for update must precede a conditional update on the same variable");
    }

    @Test
    void aProjectedResponseWithoutAViewOutputIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals("GetCourse")
                        ? (SpringBootDeclaration) new SpringBootDeclaration.CapabilityDeclaration(
                                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                                capability.serviceName(), capability.controllerName(), capability.methodName(),
                                capability.httpMethod(), capability.route(), capability.kind(),
                                capability.transactionMode(), capability.authenticated(), capability.actor(),
                                capability.input(), capability.actorBinding(),
                                new TransportPlan(capability.transportPlan().inputBinding(),
                                        TransportPlan.ResponseRepresentation.PROJECTION),
                                new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG),
                                capability.failures(), capability.workflow())
                        : declaration)
                .toList();

        assertRejected(
                replace(model, declarations),
                "a projected response must declare a view output",
                "must be VALUE for outputType");
    }

    @Test
    void aPresenceTestOverSomethingOutsideTheChangeSetIsRejected() {
        assertRejected(
                withValidateStep(LoweringTestSupport.lowerSuccess(SOURCE), "UpdateCourse", step -> new SpringBootWorkflow.ValidateStep(
                        step.id(), step.origin(),
                        new SpringExpression.PayloadPresence(
                                step.condition().id(), step.condition().origin(), step.condition().type(), "code"),
                        step.errorSymbol())),
                "an unknown change set property");
    }

    @Test
    void aPatchChangeOutsideThePayloadFieldsIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.InputDeclaration input
                        && input.javaName().equals("UpdateCourseInput")
                        ? (SpringBootDeclaration) new SpringBootDeclaration.InputDeclaration(
                                input.id(), input.origin(), input.sourceSymbol(), input.javaName(), input.fields(),
                                input.patch().map(patch -> new SpringBootDeclaration.PatchSpec(
                                        patch.sourceEntitySymbol(), patch.sourceEntityJavaName(),
                                        patch.identityFieldSymbol(), patch.identityPropertyName(),
                                        patch.changesPropertyName(), patch.expectedVersionPropertyName(),
                                        List.of(patch.changes().getFirst(), patch.changes().getFirst()))))
                        : declaration)
                .toList();

        assertRejected(replace(model, declarations), "a patch change must appear once per payload field");
    }

    private static List<LoweringDiagnostic> validate(SpringBootLoweredModel model) {
        return new SpringBootLoweredIrValidator().validate(model);
    }

    /**
     * Requires the damaged model to be rejected, through exactly the named facts.
     *
     * <p>More than one fragment is listed when one damage is genuinely visible to more than one
     * invariant; each must be reported exactly once, so a validator that only noticed the first would
     * still fail here.
     */
    private static void assertRejected(SpringBootLoweredModel model, String... messageFragments) {
        List<LoweringDiagnostic> diagnostics = validate(model);
        for (String messageFragment : messageFragments) {
            List<LoweringDiagnostic> matching = diagnostics.stream()
                    .filter(diagnostic -> diagnostic.message().contains(messageFragment))
                    .toList();
            assertEquals(
                    1,
                    matching.size(),
                    () -> "expected exactly one diagnostic containing '" + messageFragment + "' but got " + diagnostics);
            assertEquals("SIR-LOWER-IR-001", matching.getFirst().code().value());
        }
    }

    private static SpringBootLoweredModel replace(SpringBootLoweredModel model, List<SpringBootDeclaration> declarations) {
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static List<String> presenceProperties(SpringExpression expression) {
        return switch (expression) {
            case SpringExpression.PayloadPresence presence -> List.of(presence.sourcePropertyName());
            case SpringExpression.BinaryExpression binary -> concat(
                    presenceProperties(binary.left()), presenceProperties(binary.right()));
            case SpringExpression.UnaryExpression unary -> presenceProperties(unary.operand());
            default -> List.of();
        };
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> joined = new java.util.ArrayList<>(left);
        joined.addAll(right);
        return joined;
    }

    private static SpringBootWorkflow.ConditionalUpdate conditionalUpdate(SpringBootLoweredModel model, String capabilityName) {
        return persistStep(model, capabilityName).conditional().orElseThrow();
    }

    private static SpringBootWorkflow.PersistStep persistStep(SpringBootLoweredModel model, String capabilityName) {
        return capability(model, capabilityName).workflow().steps().stream()
                .filter(SpringBootWorkflow.PersistStep.class::isInstance)
                .map(SpringBootWorkflow.PersistStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootLoweredModel withUpdateStep(
            SpringBootLoweredModel model,
            String capabilityName,
            UnaryOperator<SpringBootWorkflow.UpdateStep> change
    ) {
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals(capabilityName)
                        ? (SpringBootDeclaration) capabilityWithWorkflow(capability, capability.workflow().steps().stream()
                                .map(step -> step instanceof SpringBootWorkflow.UpdateStep update
                                        ? (SpringBootWorkflow.Step) change.apply(update)
                                        : step)
                                .toList())
                        : declaration)
                .toList();
        return replace(model, declarations);
    }

    private static SpringBootLoweredModel withLoadStep(
            SpringBootLoweredModel model,
            String capabilityName,
            UnaryOperator<SpringBootWorkflow.LoadStep> change
    ) {
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals(capabilityName)
                        ? (SpringBootDeclaration) capabilityWithWorkflow(capability, capability.workflow().steps().stream()
                                .map(step -> step instanceof SpringBootWorkflow.LoadStep load
                                        ? (SpringBootWorkflow.Step) change.apply(load)
                                        : step)
                                .toList())
                        : declaration)
                .toList();
        return replace(model, declarations);
    }

    private static SpringBootLoweredModel withValidateStep(
            SpringBootLoweredModel model,
            String capabilityName,
            UnaryOperator<SpringBootWorkflow.ValidateStep> change
    ) {
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals(capabilityName)
                        ? (SpringBootDeclaration) capabilityWithWorkflow(capability, capability.workflow().steps().stream()
                                .map(step -> step instanceof SpringBootWorkflow.ValidateStep validate
                                        ? (SpringBootWorkflow.Step) change.apply(validate)
                                        : step)
                                .toList())
                        : declaration)
                .toList();
        return replace(model, declarations);
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

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.EntityDeclaration entity(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.PatchSpec patchSpec(SpringBootLoweredModel model, String payloadName) {
        return payload(model, payloadName).patch().orElseThrow(
                () -> new AssertionError(payloadName + " must carry a patch spec"));
    }

    private static SpringBootDeclaration.InputDeclaration payload(SpringBootLoweredModel model, String payloadName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
                .map(SpringBootDeclaration.InputDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(payloadName))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.HttpMethod httpMethod(SpringBootLoweredModel model, String capabilityName) {
        return capability(model, capabilityName).httpMethod();
    }

    private static TransportPlan transportPlan(SpringBootLoweredModel model, String capabilityName) {
        return capability(model, capabilityName).transportPlan();
    }

    private static SpringBootDeclaration.HttpStatus status(SpringBootLoweredModel model, String javaName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ErrorDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ErrorDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(javaName))
                .map(SpringBootDeclaration.ErrorDeclaration::httpStatus)
                .findFirst()
                .orElseThrow();
    }

    private static String name(SpringBootLoweredModel model, String javaName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ErrorDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ErrorDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(javaName))
                .map(candidate -> candidate.sourceSymbol().value())
                .findFirst()
                .orElseThrow();
    }
}
