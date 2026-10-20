package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import org.junit.jupiter.api.Test;

/**
 * A column changed by an amount, lowered: the binding says which way and by how much, the plain bindings say
 * nothing, and a model that would be written as something other than one statement is rejected.
 */
class AtomicCounterLoweringTest {
    private static final String SOURCE = "valid/atomic-counter.sir";

    @Test
    void theBindingCarriesTheDirectionAndTheAmount() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.Relative take = relative(model, "Take");
        SpringBootWorkflow.Relative leave = relative(model, "Leave");
        assertFalse(take.subtract());
        assertTrue(leave.subtract());
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void theWholeExpressionStaysForTheRowInMemory() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringBootWorkflow.Binding binding = update(model, "Take").bindings().get(0);
        assertTrue(binding.value() instanceof io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryExpression);
        assertEquals("enrolled", binding.targetProperty());
    }

    @Test
    void anUpdateNothingPersistsPlainlyIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        io.kcg.sir.semantic.symbol.SymbolId target = update(model, "Leave").targetVariable();

        SpringBootDeclaration.CapabilityDeclaration capability = capability(model, "Leave");
        java.util.List<SpringBootWorkflow.Step> steps = capability.workflow().steps().stream()
                .map(step -> step instanceof SpringBootWorkflow.PersistStep persist && persist.targetVariable().equals(target)
                        ? (SpringBootWorkflow.Step) new SpringBootWorkflow.PersistStep(
                                persist.id(), persist.origin(), persist.targetVariable(), PersistenceAction.INSERT)
                        : step)
                .toList();
        SpringBootWorkflow workflow = new SpringBootWorkflow(
                capability.workflow().id(), capability.workflow().origin(), capability.workflow().variables(), steps);
        SpringBootDeclaration.CapabilityDeclaration damaged = new SpringBootDeclaration.CapabilityDeclaration(
                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                capability.serviceName(), capability.controllerName(), capability.methodName(), capability.httpMethod(),
                capability.route(), capability.kind(), capability.transactionMode(), capability.authenticated(),
                capability.actor(), capability.input(), capability.actorBinding(), capability.transportPlan(),
                capability.outputType(), capability.failures(), workflow);
        SpringBootLoweredModel broken = new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                model.declarations().stream().map(declaration -> declaration == capability ? (SpringBootDeclaration) damaged : declaration).toList(),
                model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());

        assertTrue(new SpringBootLoweredIrValidator().validate(broken).stream().anyMatch(diagnostic -> diagnostic.message()
                .contains("an update that changes a column by an amount must be persisted by a plain persist")));
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootWorkflow.Relative relative(SpringBootLoweredModel model, String capability) {
        return update(model, capability).bindings().stream()
                .map(SpringBootWorkflow.Binding::relative)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootWorkflow.UpdateStep update(SpringBootLoweredModel model, String capability) {
        return capability(model, capability).workflow().steps().stream()
                .filter(SpringBootWorkflow.UpdateStep.class::isInstance)
                .map(SpringBootWorkflow.UpdateStep.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
