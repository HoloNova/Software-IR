package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.model.*;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import org.junit.jupiter.api.Test;

class ExplicitLockLoweringTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"transaction", "unlocked", "key", "query", "cache"})
    void independentlyConstructedIrCannotEvadeLockRequirements(String change) throws Exception {
        var model = LoweringTestSupport.lowerSuccess("valid/explicit-lock.sir");
        var cap = model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        var workflow = cap.workflow();
        var load = workflow.steps().stream().filter(SpringBootWorkflow.LoadStep.class::isInstance)
                .map(SpringBootWorkflow.LoadStep.class::cast).findFirst().orElseThrow();
        if (change.equals("cache")) {
            var artifacts = new java.util.ArrayList<ProjectArtifact>();
            for (var artifact : model.projectArtifacts()) artifacts.add(artifact instanceof ProjectArtifact.ApplicationConfig config
                    ? copy(config, "statementScopedQueryCache", false) : artifact);
            var broken = copy(model, "projectArtifacts", artifacts);
            assertTrue(new SpringBootLoweredIrValidator().validate(broken).stream().anyMatch(d -> d.message().contains("explicit lock")));
            return;
        }
        var changed = switch (change) {
            case "transaction" -> copy(cap, "transactionMode", SpringBootDeclaration.TransactionMode.REQUIRED);
            case "query" -> copy(cap, "httpMethod", SpringBootDeclaration.HttpMethod.GET);
            default -> {
                var bad = change.equals("unlocked") ? copy(load, "forUpdate", false)
                        : copy(load, "keyField", java.util.Optional.of(load.entitySymbol()));
                var steps = workflow.steps().stream().map(s -> s == load ? bad : s).toList();
                yield copy(cap, "workflow", copy(workflow, "steps", steps));
            }
        };
        var broken = copy(model, "declarations", model.declarations().stream().map(d -> d == cap ? changed : d).toList());
        assertTrue(new SpringBootLoweredIrValidator().validate(broken).stream()
                .anyMatch(d -> d.message().contains("explicit lock")));
    }

    @SuppressWarnings("unchecked")
    private static <T> T copy(T record, String field, Object value) throws Exception {
        var parts = record.getClass().getRecordComponents();
        var types = new Class<?>[parts.length]; var values = new Object[parts.length];
        for (int i=0; i<parts.length; i++) {
            types[i] = parts[i].getType();
            values[i] = parts[i].getName().equals(field) ? value : parts[i].getAccessor().invoke(record);
        }
        return (T) record.getClass().getDeclaredConstructor(types).newInstance(values);
    }

    @Test void scopesDoNotDiscardTheAuthoredLock() {
        String source = LoweringTestSupport.source("valid/explicit-lock.sir").replace("field name: Text unique;", "field name: Text unique; scope name != \"hidden\";");
        var model = LoweringTestSupport.lowerSourceSuccess(source);
        assertTrue(model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).flatMap(cap -> cap.workflow().steps().stream())
                .filter(SpringBootWorkflow.LoadStep.class::isInstance).map(SpringBootWorkflow.LoadStep.class::cast)
                .anyMatch(SpringBootWorkflow.LoadStep::explicitLock));
    }

    @Test void constructedCoreModelCannotRequestLocksWithoutAtomic() throws Exception {
        var model = LoweringTestSupport.normalized("valid/explicit-lock.sir");
        var cap = model.declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedCapability.class::isInstance)
                .map(io.kcg.sir.semantic.model.NormalizedCapability.class::cast).findFirst().orElseThrow();
        var altered = copy(cap, "requires", java.util.List.of());
        var broken = copy(model, "declarations", model.declarations().stream().map(d -> d == cap ? altered : d).toList());
        assertTrue(new io.kcg.sir.lowering.springboot.internal.SpringBootInputValidator(broken).validate().stream()
                .anyMatch(d -> d.message().contains("explicit lock")));
    }

    @Test void identityLockCarriesItsPhysicalQueryAndIsolation() {
        var model = LoweringTestSupport.lowerSuccess("valid/explicit-lock.sir");
        var cap = model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        assertEquals(SpringBootDeclaration.TransactionMode.READ_COMMITTED, cap.transactionMode());
        var load = cap.workflow().steps().stream().filter(SpringBootWorkflow.LoadStep.class::isInstance)
                .map(SpringBootWorkflow.LoadStep.class::cast).findFirst().orElseThrow();
        assertTrue(load.forUpdate()); assertTrue(load.explicitLock()); assertTrue(load.keyField().isEmpty());
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }
}
