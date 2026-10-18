package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A credential entity, lowered: the project gets the authenticator for exactly the credential entities
 * declared, and a reference field bound from a row held in a variable stores that row's identity (the
 * actor, which already stands for an identity, is left alone).
 */
class CredentialLoweringTest {
    private static final String SOURCE = "valid/bearer-credential.sir";

    @Test
    void theAuthenticatorKnowsTheCredentialEntityAndWhoItAuthenticates() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        ProjectArtifact.CredentialIdentityAdapter adapter = model.projectArtifacts().stream()
                .filter(ProjectArtifact.CredentialIdentityAdapter.class::isInstance)
                .map(ProjectArtifact.CredentialIdentityAdapter.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(1, adapter.sources().size());
        ProjectArtifact.CredentialSource source = adapter.sources().get(0);
        assertEquals(entity(model, "User").sourceSymbol(), source.actorEntity());
        assertEquals(entity(model, "AccessToken").sourceSymbol(), source.credentialEntity());
        assertEquals(property(entity(model, "AccessToken"), "ownerId"), source.subjectField());
        assertEquals(property(entity(model, "AccessToken"), "expiresAt"), source.expiryField());
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void aProjectWithoutACredentialEntityGetsNoAuthenticator() {
        assertTrue(LoweringTestSupport.lowerSuccess("valid/campus-market.sir").projectArtifacts().stream()
                .noneMatch(ProjectArtifact.CredentialIdentityAdapter.class::isInstance));
    }

    /** Login alone, before any capability asks who is calling: nothing would use an authenticator yet. */
    @Test
    void aCredentialEntityWithoutACapabilityThatHasAnActorGetsNoAuthenticator() {
        String source = LoweringTestSupport.source(SOURCE);
        String withoutActor = source.substring(0, source.indexOf("    capability PostNote {")) + "  }\n}\n";
        SpringBootLoweredModel model = LoweringTestSupport.lowerSourceSuccess(withoutActor);
        assertTrue(model.projectArtifacts().stream().noneMatch(ProjectArtifact.CredentialIdentityAdapter.class::isInstance));
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void aReferenceBoundFromARowStoresTheRowsIdentityButTheActorIsKeptAsIs() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringExpression fromRow = binding(model, "Login", "ownerId");
        SpringExpression.MemberExpression identity = assertInstanceOf(SpringExpression.MemberExpression.class, fromRow);
        assertEquals("id", identity.targetMember());
        assertInstanceOf(SpringExpression.NameExpression.class, identity.receiver());

        SpringExpression fromActor = binding(model, "PostNote", "authorId");
        assertInstanceOf(SpringExpression.NameExpression.class, fromActor);
    }

    private static SpringExpression binding(SpringBootLoweredModel model, String capabilityName, String property) {
        List<SpringBootWorkflow.Step> steps = model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals(capabilityName))
                .findFirst()
                .orElseThrow()
                .workflow()
                .steps();
        return steps.stream()
                .filter(SpringBootWorkflow.CreateStep.class::isInstance)
                .map(SpringBootWorkflow.CreateStep.class::cast)
                .flatMap(create -> create.bindings().stream())
                .filter(binding -> binding.targetProperty().equals(property))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static SpringBootDeclaration.EntityDeclaration entity(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .filter(entity -> entity.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static io.kcg.sir.semantic.symbol.SymbolId property(SpringBootDeclaration.EntityDeclaration entity, String javaName) {
        return entity.fields().stream().filter(field -> field.javaName().equals(javaName)).findFirst().orElseThrow().sourceSymbol();
    }
}
