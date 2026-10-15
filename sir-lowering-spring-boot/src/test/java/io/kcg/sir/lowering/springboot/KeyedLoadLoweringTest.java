package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A load by a unique field, lowered: the key survives into the lowered step, never takes the row
 * lock, and a lowered model that breaks either fact is rejected before anything is generated.
 */
class KeyedLoadLoweringTest {
    private static final String SOURCE = "valid/unique-lookup.sir";

    @Test
    void theKeyFieldIsCarriedAndAKeyedLoadDoesNotLockTheRow() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootDeclaration.EntityDeclaration account = entity(model, "Account");

        SpringBootWorkflow.LoadStep keyed = load(model, "FindAccountByName");
        assertEquals(
                account.fields().stream().filter(property -> property.javaName().equals("name")).findFirst().orElseThrow().sourceSymbol(),
                keyed.keyField().orElseThrow());
        assertFalse(keyed.forUpdate());
        assertTrue(load(model, "GetAccount").keyField().isEmpty(), "a load without a field name is by identity");
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void aKeyThatIsNotAStoredFieldOfTheLoadedEntityIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SymbolId identity = entity(model, "Account").identity().sourceSymbol();

        assertRejected(withLoad(model, "FindAccountByName", load -> withKey(load, identity, load.forUpdate())),
                "load key is not a stored field of the loaded entity");
    }

    @Test
    void aLoadByAFieldCannotAlsoBeALoadForUpdate() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        assertRejected(withLoad(model, "FindAccountByName", load -> withKey(load, load.keyField().orElseThrow(), true)),
                "a load by a field cannot be a load for update");
    }

    private static SpringBootWorkflow.LoadStep withKey(SpringBootWorkflow.LoadStep load, SymbolId key, boolean forUpdate) {
        return new SpringBootWorkflow.LoadStep(
                load.id(), load.origin(), load.entitySymbol(), load.idExpression(), load.result(), load.errorSymbol(),
                forUpdate, Optional.of(key));
    }

    private static void assertRejected(SpringBootLoweredModel model, String messageFragment) {
        List<LoweringDiagnostic> diagnostics = new SpringBootLoweredIrValidator().validate(model);
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(messageFragment)),
                () -> "expected a diagnostic containing '" + messageFragment + "' but got " + diagnostics);
    }

    private static SpringBootLoweredModel withLoad(
            SpringBootLoweredModel model, String capabilityName, UnaryOperator<SpringBootWorkflow.LoadStep> change) {
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals(capabilityName)
                        ? (SpringBootDeclaration) withSteps(capability, capability.workflow().steps().stream()
                                .map(step -> step instanceof SpringBootWorkflow.LoadStep load
                                        ? (SpringBootWorkflow.Step) change.apply(load)
                                        : step)
                                .toList())
                        : declaration)
                .toList();
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static SpringBootDeclaration.CapabilityDeclaration withSteps(
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

    private static SpringBootWorkflow.LoadStep load(SpringBootLoweredModel model, String capabilityName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals(capabilityName))
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.LoadStep.class::isInstance)
                .map(SpringBootWorkflow.LoadStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.EntityDeclaration entity(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .filter(entity -> entity.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
