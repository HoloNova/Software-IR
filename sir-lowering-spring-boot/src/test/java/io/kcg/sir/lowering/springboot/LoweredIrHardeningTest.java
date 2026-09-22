package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LoweredIrHardeningTest {

    @Nested
    class ProjectArtifactTest {

        @Test
        void mavenProjectHasNoSyntheticOwnerSymbolAndCompleteFields() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            ProjectArtifact.MavenProject project = model.mavenProject();

            assertNotNull(project);
            assertTrue(project.origin().ownerSymbol().isEmpty(),
                    "MavenProject must not carry a synthetic sir:// owner SymbolId");
            assertFalse(project.groupId().isBlank(), "groupId must be non-blank");
            assertFalse(project.artifactId().isBlank(), "artifactId must be non-blank");
            assertFalse(project.version().isBlank(), "version must be non-blank");
            assertEquals(21, project.javaVersion());
            assertEquals("3.5.3", project.springBootParentVersion());
        }

        @Test
        void mavenProjectDependenciesAndPluginsAreNonEmptyAndUnmodifiable() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            ProjectArtifact.MavenProject project = model.mavenProject();

            assertFalse(project.dependencies().isEmpty(), "dependencies must be non-empty");
            assertFalse(project.plugins().isEmpty(), "plugins must be non-empty");
            assertThrows(UnsupportedOperationException.class, () -> project.dependencies().add(null));
            assertThrows(UnsupportedOperationException.class, () -> project.plugins().add(null));
        }

        @Test
        void mybatisPlusDependencyHasExpectedVersion() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            ProjectArtifact.MavenDependency mybatisPlus = model.mavenProject().dependencies().stream()
                    .filter(dep -> dep.artifactId().equals("mybatis-plus-spring-boot3-starter"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("3.5.12", mybatisPlus.version());
            assertEquals("com.baomidou", mybatisPlus.groupId());
        }

        @Test
        void applicationMainHasNoOwnerSymbolAndCorrectNaming() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            ProjectArtifact.ApplicationMain app = model.applicationMain();

            assertNotNull(app);
            assertTrue(app.origin().ownerSymbol().isEmpty(),
                    "ApplicationMain must not carry a synthetic sir:// owner SymbolId");
            assertFalse(app.packageName().isBlank(), "packageName must be non-blank");
            assertEquals("Application", app.simpleName());
            assertTrue(app.mapperScanPackage().endsWith(".persistence"),
                    "mapperScanPackage must end with '.persistence'");
            assertTrue(app.path().endsWith("Application.java"),
                    "path must end with 'Application.java'");
        }
    }

    @Nested
    class ActorBindingTest {

        @Test
        void campusMarketActorBindingIsPresentWithLongIdentityStorage() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            SpringBootDeclaration.CapabilityDeclaration capability = capability(model, "PublishGoods");

            Optional<io.kcg.sir.lowering.springboot.model.ActorBinding> binding = capability.actorBinding();
            assertTrue(binding.isPresent(), "capability with actor must have actorBinding");

            assertEquals("actorId", binding.get().attributeName());
            LoweredJavaType.Scalar storage = binding.get().identityStorageType();
            assertEquals(LoweredJavaType.ScalarKind.LONG, storage.kind(),
                    "User identity is Int64 which lowers to LONG");
        }

        @Test
        void allWorkflowStepsActorBindingIsAbsentWhenNoActor() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
            SpringBootDeclaration.CapabilityDeclaration capability = capability(model, "ExerciseSteps");

            assertTrue(capability.actorBinding().isEmpty(),
                    "capability without actor must not have actorBinding");
        }
    }

    @Nested
    class TransportPlanTest {

        @Test
        void campusMarketUsesRequestBodyAndEntityBody() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            TransportPlan plan = capability(model, "PublishGoods").transportPlan();

            assertEquals(TransportPlan.InputBinding.REQUEST_BODY, plan.inputBinding(),
                    "POST command with input must use REQUEST_BODY");
            assertEquals(TransportPlan.ResponseRepresentation.ENTITY_BODY, plan.responseRepresentation(),
                    "output Ref<Goods> must lower to ENTITY_BODY");
        }

        @Test
        void allWorkflowStepsUsesRequestBodyAndList() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
            TransportPlan plan = capability(model, "ExerciseSteps").transportPlan();

            assertEquals(TransportPlan.InputBinding.REQUEST_BODY, plan.inputBinding(),
                    "POST command with input must use REQUEST_BODY");
            assertEquals(TransportPlan.ResponseRepresentation.LIST, plan.responseRepresentation(),
                    "output List<Ref<User>> must lower to LIST");
        }
    }

    @Nested
    class FindItemBindingTest {

        @Test
        void findStepItemVariableIsBoundBySymbolIdWithDeclaredEntityType() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
            SpringBootWorkflow workflow = capability(model, "ExerciseSteps").workflow();
            SpringBootWorkflow.FindStep findStep = findStep(workflow);

            assertNotNull(findStep.itemVariable(), "FindStep itemVariable must not be null");
            assertNotNull(findStep.itemVariable().symbol(), "itemVariable symbol must not be null");

            LoweredJavaType.Declared itemType =
                    assertInstanceOf(LoweredJavaType.Declared.class, findStep.itemVariable().type(),
                            "itemVariable type must be Declared (NOT EntityReference)");
            assertEquals(LoweredJavaType.DeclaredKind.ENTITY, itemType.kind(),
                    "itemVariable must hold the full entity, lowered to Declared[ENTITY]");

            assertEquals("item", findStep.itemVariable().targetName(),
                    "itemVariable targetName is 'item' (binding is by SymbolId, not by name)");
        }
    }

    @Nested
    class PersistActionTest {

        @Test
        void loadUpdatePersistProducesUpdateAction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
            SpringBootWorkflow workflow = capability(model, "ExerciseSteps").workflow();
            SpringBootWorkflow.PersistStep persist = singlePersistStep(workflow);

            assertEquals(PersistenceAction.UPDATE, persist.action(),
                    "Load result is EXISTING, so persist must be UPDATE");
        }

        @Test
        void createAndPersistProducesInsertAction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/persist-provenance.sir");
            SpringBootWorkflow workflow = capability(model, "CreateAndPersist").workflow();
            SpringBootWorkflow.PersistStep persist = singlePersistStep(workflow);

            assertEquals(PersistenceAction.INSERT, persist.action(),
                    "Create result is NEW, so persist must be INSERT");
        }

        @Test
        void loadUpdatePersistInProvenanceFileProducesUpdateAction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/persist-provenance.sir");
            SpringBootWorkflow workflow = capability(model, "LoadUpdatePersist").workflow();
            SpringBootWorkflow.PersistStep persist = singlePersistStep(workflow);

            assertEquals(PersistenceAction.UPDATE, persist.action(),
                    "Load result is EXISTING, so persist must be UPDATE");
        }

        @Test
        void createUpdatePersistProducesInsertThenUpdate() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/persist-provenance.sir");
            SpringBootWorkflow workflow = capability(model, "CreateUpdatePersist").workflow();
            List<SpringBootWorkflow.PersistStep> steps = persistSteps(workflow);

            assertEquals(2, steps.size(), "CreateUpdatePersist must have two persist steps");
            assertEquals(PersistenceAction.INSERT, steps.get(0).action(),
                    "first persist after Create (NEW) must be INSERT");
            assertEquals(PersistenceAction.UPDATE, steps.get(1).action(),
                    "second persist after first persist (now EXISTING) must be UPDATE");
        }
    }

    @Nested
    class InputRefTest {

        @Test
        void inputRefFieldLowersToAccountIdWithLongIdentityStorage() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/input-ref-test.sir");
            SpringBootDeclaration.InputDeclaration input = input(model, "LinkInput");

            SpringBootDeclaration.Property account = input.fields().stream()
                    .filter(field -> field.javaName().equals("accountId"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Ref<Account> field 'account' must lower to javaName 'accountId'"));

            LoweredJavaType.EntityReference refType =
                    assertInstanceOf(LoweredJavaType.EntityReference.class, account.type(),
                            "input Ref field type must be EntityReference");
            assertEquals(LoweredJavaType.ScalarKind.LONG, refType.identityStorageType().kind(),
                    "Account identity is Int64 which lowers to LONG");
        }
    }

    @Nested
    class ValidatorHardeningTest {

        @Test
        void nullMavenProjectIsRejectedAtConstruction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            assertThrows(NullPointerException.class, () -> new SpringBootLoweredModel(
                    model.irVersion(), model.profile(), model.softwareName(), model.displayName(),
                    model.basePackage(), model.declarations(), model.artifacts(),
                    null, model.applicationMain()));
        }

        @Test
        void nullApplicationMainIsRejectedAtConstruction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
            assertThrows(NullPointerException.class, () -> new SpringBootLoweredModel(
                    model.irVersion(), model.profile(), model.softwareName(), model.displayName(),
                    model.basePackage(), model.declarations(), model.artifacts(),
                    model.mavenProject(), null));
        }

        @Test
        void persistStepWithNullActionIsRejectedAtConstruction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
            SpringBootWorkflow.PersistStep valid = singlePersistStep(
                    capability(model, "ExerciseSteps").workflow());
            assertThrows(NullPointerException.class, () -> new SpringBootWorkflow.PersistStep(
                    valid.id(), valid.origin(), valid.targetVariable(), null));
        }

        @Test
        void findStepWithNullItemVariableIsRejectedAtConstruction() {
            SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/all-workflow-steps.sir");
            SpringBootWorkflow.FindStep valid = findStep(
                    capability(model, "ExerciseSteps").workflow());
            assertThrows(NullPointerException.class, () -> new SpringBootWorkflow.FindStep(
                    valid.id(), valid.origin(), valid.entitySymbol(), valid.predicate(),
                    valid.orderKeys(), valid.page(), valid.stringMatches(),
                    valid.result(), null));
        }

        @Test
        void transportPlanWithNullInputBindingIsRejectedAtConstruction() {
            assertThrows(NullPointerException.class, () -> new TransportPlan(
                    null, TransportPlan.ResponseRepresentation.VOID));
        }
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(
            SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(value -> value.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.InputDeclaration input(
            SpringBootLoweredModel model, String name) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
                .map(SpringBootDeclaration.InputDeclaration.class::cast)
                .filter(value -> value.javaName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootWorkflow.FindStep findStep(SpringBootWorkflow workflow) {
        return workflow.steps().stream()
                .filter(SpringBootWorkflow.FindStep.class::isInstance)
                .map(SpringBootWorkflow.FindStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootWorkflow.PersistStep singlePersistStep(SpringBootWorkflow workflow) {
        return persistSteps(workflow).stream()
                .reduce((first, second) -> {
                    throw new AssertionError("expected exactly one PersistStep but found multiple");
                })
                .orElseThrow(() -> new AssertionError("expected one PersistStep but found none"));
    }

    private static List<SpringBootWorkflow.PersistStep> persistSteps(SpringBootWorkflow workflow) {
        return workflow.steps().stream()
                .filter(SpringBootWorkflow.PersistStep.class::isInstance)
                .map(SpringBootWorkflow.PersistStep.class::cast)
                .toList();
    }
}