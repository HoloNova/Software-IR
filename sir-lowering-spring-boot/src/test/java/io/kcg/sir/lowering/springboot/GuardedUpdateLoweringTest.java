package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A guarded update, lowered: the guard and its error reach the lowered step, and a lowered model that
 * asks for a guard the generated statement could not honour is rejected before anything is generated.
 */
class GuardedUpdateLoweringTest {
    private static final String SOURCE = "valid/guarded-update.sir";

    @Test
    void theGuardAndItsErrorSurviveLowering() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.UpdateStep update = update(model);
        SpringBootWorkflow.UpdateGuard guard = update.guard().orElseThrow();
        assertTrue(update.conditional().isEmpty(), "a guard and a version check are different protections");
        assertEquals("Invalid", error(model, guard.failure()).javaName().replace("Exception", ""));
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void aGuardTogetherWithAVersionCheckIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.ConditionalUpdate version = new SpringBootWorkflow.ConditionalUpdate(
                update(model).targetVariable(), "credential", update(model).targetVariable(), "id", "id",
                update(model).targetVariable(), "version", "version", 1L, java.util.Optional.empty(),
                List.of(new SpringBootWorkflow.ColumnAssignment(update(model).targetVariable(), "used", "used", "used")));

        assertRejected(
                withStep(model, SpringBootWorkflow.UpdateStep.class, step -> new SpringBootWorkflow.UpdateStep(
                        step.id(), step.origin(), step.targetVariable(), step.bindings(),
                        java.util.Optional.of(version), step.guard())),
                "an update cannot carry both a version check and a guard");
    }

    @Test
    void aGuardNothingPersistsIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SymbolIdHolder target = new SymbolIdHolder(update(model).targetVariable());

        assertRejected(
                withStep(model, SpringBootWorkflow.PersistStep.class, step -> step.targetVariable().equals(target.symbol())
                        ? new SpringBootWorkflow.PersistStep(
                                step.id(), step.origin(), step.targetVariable(), PersistenceAction.INSERT)
                        : step),
                "a guarded update must be persisted by a plain persist of the same row");
    }

    private record SymbolIdHolder(io.kcg.sir.semantic.symbol.SymbolId symbol) {
    }

    private static SpringBootWorkflow.UpdateStep update(SpringBootLoweredModel model) {
        return capability(model).workflow().steps().stream()
                .filter(SpringBootWorkflow.UpdateStep.class::isInstance)
                .map(SpringBootWorkflow.UpdateStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.ErrorDeclaration error(SpringBootLoweredModel model, io.kcg.sir.semantic.symbol.SymbolId symbol) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.ErrorDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ErrorDeclaration.class::cast)
                .filter(declaration -> declaration.sourceSymbol().equals(symbol))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals("Refresh"))
                .findFirst()
                .orElseThrow();
    }

    /** The model with every step of one kind replaced by what the change makes of it. */
    private static <T extends SpringBootWorkflow.Step> SpringBootLoweredModel withStep(
            SpringBootLoweredModel model, Class<T> kind, UnaryOperator<T> change) {
        SpringBootDeclaration.CapabilityDeclaration capability = capability(model);
        List<SpringBootWorkflow.Step> steps = capability.workflow().steps().stream()
                .map(step -> kind.isInstance(step) ? (SpringBootWorkflow.Step) change.apply(kind.cast(step)) : step)
                .toList();
        SpringBootWorkflow workflow = new SpringBootWorkflow(
                capability.workflow().id(), capability.workflow().origin(), capability.workflow().variables(), steps);
        SpringBootDeclaration.CapabilityDeclaration damaged = new SpringBootDeclaration.CapabilityDeclaration(
                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                capability.serviceName(), capability.controllerName(), capability.methodName(), capability.httpMethod(),
                capability.route(), capability.kind(), capability.transactionMode(), capability.authenticated(),
                capability.actor(), capability.input(), capability.actorBinding(), capability.transportPlan(),
                capability.outputType(), capability.failures(), workflow);
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration == capability ? (SpringBootDeclaration) damaged : declaration)
                .toList();
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static void assertRejected(SpringBootLoweredModel model, String messageFragment) {
        List<LoweringDiagnostic> diagnostics = new SpringBootLoweredIrValidator().validate(model);
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(messageFragment)),
                () -> "expected a diagnostic containing '" + messageFragment + "' but got " + diagnostics);
    }
}
