package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringBootLoweringIntegrationTest {

    @Test
    void lowersCampusMarketIntoCompleteSpringBootConstructionModel() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");

        assertEquals("CampusMarket", model.softwareName());
        assertEquals("com.example.campusmarket", model.basePackage());
        assertEquals(6, model.declarations().size());
        assertEquals(9, model.artifacts().size());
        assertEquals(List.of(
                        SpringArtifact.Role.ENUM,
                        SpringArtifact.Role.ENTITY_MODEL,
                        SpringArtifact.Role.MAPPER,
                        SpringArtifact.Role.ENTITY_MODEL,
                        SpringArtifact.Role.MAPPER,
                        SpringArtifact.Role.REQUEST_DTO,
                        SpringArtifact.Role.EXCEPTION,
                        SpringArtifact.Role.SERVICE,
                        SpringArtifact.Role.CONTROLLER),
                model.artifacts().stream().map(SpringArtifact::role).toList());

        SpringBootDeclaration.EntityDeclaration goods = entity(model, "Goods");
        SpringBootDeclaration.Property seller = goods.fields().stream()
                .filter(field -> field.javaName().equals("sellerId"))
                .findFirst()
                .orElseThrow();
        LoweredJavaType.EntityReference sellerType =
                assertInstanceOf(LoweredJavaType.EntityReference.class, seller.type());
        assertEquals(LoweredJavaType.ScalarKind.LONG, sellerType.identityStorageType().kind());
        assertEquals("seller_id", seller.columnName().orElseThrow());
        assertEquals(SpringBootDeclaration.PersistenceShape.REFERENCE_ID, seller.persistenceShape());

        SpringBootDeclaration.CapabilityDeclaration capability = capability(model, "PublishGoods");
        assertEquals(SpringBootDeclaration.HttpMethod.POST, capability.httpMethod());
        assertEquals("/api/publish-goods", capability.route());
        assertEquals(SpringBootDeclaration.TransactionMode.REQUIRED, capability.transactionMode());
        assertTrue(capability.authenticated());
        assertEquals(4, capability.workflow().steps().size());
        assertInstanceOf(SpringBootWorkflow.ValidateStep.class, capability.workflow().steps().get(0));
        assertInstanceOf(SpringBootWorkflow.CreateStep.class, capability.workflow().steps().get(1));
        SpringBootWorkflow.CreateStep create =
                (SpringBootWorkflow.CreateStep) capability.workflow().steps().get(1);
        assertEquals("sellerId", create.bindings().stream()
                .filter(binding -> binding.fieldSymbol().equals(seller.sourceSymbol()))
                .findFirst().orElseThrow().targetProperty());
        assertInstanceOf(SpringBootWorkflow.PersistStep.class, capability.workflow().steps().get(2));
        assertInstanceOf(SpringBootWorkflow.ReturnStep.class, capability.workflow().steps().get(3));
    }

    @Test
    void lowersLoadUpdatePersistFindAndReturnSteps() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
        List<Class<?>> stepTypes = capability(model, "ExerciseSteps").workflow().steps().stream()
                .map(Object::getClass)
                .toList();

        assertEquals(List.of(
                SpringBootWorkflow.LoadStep.class,
                SpringBootWorkflow.UpdateStep.class,
                SpringBootWorkflow.PersistStep.class,
                SpringBootWorkflow.FindStep.class,
                SpringBootWorkflow.ReturnStep.class), stepTypes);
    }

    @Test
    void mapsConstraintsWithoutLeavingGeneratorDecisions() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
        SpringBootDeclaration.Property title = entity(model, "Goods").fields().stream()
                .filter(field -> field.javaName().equals("title"))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of(
                        SpringBootDeclaration.ConstraintKind.NOT_BLANK,
                        SpringBootDeclaration.ConstraintKind.SIZE),
                title.constraints().stream().map(SpringBootDeclaration.Constraint::kind).toList());
        assertEquals(List.of("1", "100"), title.constraints().get(1).arguments());
    }

    @Test
    void allPublicCollectionsAreImmutable() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
        SpringBootDeclaration.EntityDeclaration goods = entity(model, "Goods");
        SpringBootDeclaration.CapabilityDeclaration capability = capability(model, "PublishGoods");

        assertThrows(UnsupportedOperationException.class, () -> model.declarations().add(null));
        assertThrows(UnsupportedOperationException.class, () -> model.artifacts().add(null));
        assertThrows(UnsupportedOperationException.class, () -> goods.fields().add(null));
        assertThrows(UnsupportedOperationException.class, () -> capability.workflow().steps().add(null));
        assertThrows(UnsupportedOperationException.class, () -> capability.workflow().variables().add(null));
    }

    private SpringBootDeclaration.EntityDeclaration entity(SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast)
                .filter(value -> value.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private SpringBootDeclaration.CapabilityDeclaration capability(
            SpringBootLoweredModel model,
            String name
    ) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(value -> value.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
