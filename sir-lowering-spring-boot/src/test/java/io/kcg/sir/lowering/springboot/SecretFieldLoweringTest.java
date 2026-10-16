package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A secret field, lowered: what is stored is the digest of the bound value (and only for the secret
 * field), a {@code matches} stays a match, the project gets the digest class only where a secret is
 * declared, and a lowered model that breaks the typing of either is rejected before generation.
 */
class SecretFieldLoweringTest {
    private static final String SOURCE = "valid/secret-field.sir";

    @Test
    void onlyTheBindingToTheSecretFieldIsDigested() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.CreateStep create = (SpringBootWorkflow.CreateStep) steps(model, "Register").stream()
                .filter(SpringBootWorkflow.CreateStep.class::isInstance)
                .findFirst()
                .orElseThrow();

        SpringBootWorkflow.Binding name = create.bindings().stream().filter(b -> b.targetProperty().equals("name")).findFirst().orElseThrow();
        SpringBootWorkflow.Binding secret = create.bindings().stream().filter(b -> b.targetProperty().equals("passwordHash")).findFirst().orElseThrow();
        assertTrue(!(name.value() instanceof SpringExpression.DigestExpression), "an ordinary field is stored as it is");
        SpringExpression.DigestExpression digest = assertInstanceOf(SpringExpression.DigestExpression.class, secret.value());
        assertEquals("password", secret.sourcePropertyName().orElseThrow(), "the request field is still the one named");
        assertTrue(digest.operand() instanceof SpringExpression.MemberExpression);
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void matchesIsAMatchBetweenTheStoredTextAndTheCandidate() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootWorkflow.ValidateStep check = (SpringBootWorkflow.ValidateStep) steps(model, "Login").stream()
                .filter(SpringBootWorkflow.ValidateStep.class::isInstance)
                .findFirst()
                .orElseThrow();

        SpringExpression.BinaryExpression matches = assertInstanceOf(SpringExpression.BinaryExpression.class, check.condition());
        assertEquals(SpringExpression.BinaryOperator.MATCHES, matches.operator());
    }

    @Test
    void theProjectGetsTheDigestClassOnlyWhereASecretIsDeclared() {
        SpringBootLoweredModel withSecret = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootLoweredModel without = LoweringTestSupport.lowerSuccess("valid/unique-lookup.sir");

        assertTrue(withSecret.projectArtifacts().stream().anyMatch(ProjectArtifact.SecretDigest.class::isInstance));
        assertTrue(without.projectArtifacts().stream().noneMatch(ProjectArtifact.SecretDigest.class::isInstance));
    }

    @Test
    void aDigestOfSomethingThatIsNotATextIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringBootLoweredModel damaged = replaceDigestOperand(model);

        List<LoweringDiagnostic> diagnostics = new SpringBootLoweredIrValidator().validate(damaged);
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("a digest is taken of a text and is a text")),
                () -> "got " + diagnostics);
    }

    private static SpringBootLoweredModel replaceDigestOperand(SpringBootLoweredModel model) {
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability
                        && capability.javaName().equals("Register")
                        ? (SpringBootDeclaration) withSteps(capability, capability.workflow().steps().stream()
                                .map(step -> step instanceof SpringBootWorkflow.CreateStep create ? (SpringBootWorkflow.Step) withIntegerDigest(create) : step)
                                .toList())
                        : declaration)
                .toList();
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static SpringBootWorkflow.CreateStep withIntegerDigest(SpringBootWorkflow.CreateStep create) {
        List<SpringBootWorkflow.Binding> bindings = create.bindings().stream()
                .map(binding -> binding.value() instanceof SpringExpression.DigestExpression digest
                        ? new SpringBootWorkflow.Binding(binding.fieldSymbol(), binding.targetProperty(),
                                new SpringExpression.DigestExpression(digest.id(), digest.origin(), digest.type(),
                                        new SpringExpression.IntegerLiteral(digest.id(), digest.origin(),
                                                new io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar(
                                                        io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind.INTEGER),
                                                java.math.BigInteger.ONE)),
                                binding.sourcePropertyName())
                        : binding)
                .toList();
        return new SpringBootWorkflow.CreateStep(create.id(), create.origin(), create.entitySymbol(), create.result(), bindings);
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

    private static List<SpringBootWorkflow.Step> steps(SpringBootLoweredModel model, String capabilityName) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals(capabilityName))
                .findFirst()
                .orElseThrow()
                .workflow()
                .steps();
    }
}
