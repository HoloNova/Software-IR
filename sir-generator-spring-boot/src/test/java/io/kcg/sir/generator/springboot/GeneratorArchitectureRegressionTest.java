package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorArchitectureRegressionTest {

    @Test
    void unitOutputUsesBareReturnAndVoidControllerDelegation() {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/unit-output.sir");
        String service = GeneratorTestSupport.contentEndingWith(files, "PingService.java");
        String controller = GeneratorTestSupport.contentEndingWith(files, "PingController.java");

        assertTrue(service.contains("public void ping()"));
        assertTrue(service.contains("        return;"), service);
        assertFalse(service.contains("return null;"), service);
        assertTrue(controller.contains("        service.ping();"), controller);
        assertFalse(controller.contains("return service.ping();"), controller);
    }

    @Test
    void compoundFindUsesNestedAndOrNotConsumers() {
        String service = GeneratorTestSupport.contentEndingWith(
                GeneratorTestSupport.generateSuccess("valid/compound-find.sir"),
                "FilterUsersService.java");

        assertTrue(service.contains(".and("), service);
        assertTrue(service.contains(".or("), service);
        assertTrue(service.contains(".not("), service);
        assertFalse(service.contains(".or()"), service);
    }

    @Test
    void mismatchedResponseRepresentationIsRejectedBeforeRendering() {
        SpringBootLoweredModel model = GeneratorTestSupport.lowerSuccess("valid/campus-market.sir");
        List<SpringBootDeclaration> declarations = new ArrayList<>(model.declarations());
        for (int i = 0; i < declarations.size(); i++) {
            if (declarations.get(i) instanceof SpringBootDeclaration.CapabilityDeclaration cap) {
                TransportPlan corruptedPlan = new TransportPlan(
                        cap.transportPlan().inputBinding(),
                        TransportPlan.ResponseRepresentation.VOID);
                declarations.set(i, new SpringBootDeclaration.CapabilityDeclaration(
                        cap.id(), cap.origin(), cap.sourceSymbol(), cap.javaName(),
                        cap.serviceName(), cap.controllerName(), cap.methodName(),
                        cap.httpMethod(), cap.route(), cap.kind(), cap.transactionMode(),
                        cap.authenticated(), cap.actor(), cap.input(), cap.actorBinding(),
                        corruptedPlan, cap.outputType(), cap.failures(), cap.workflow()));
            }
        }
        SpringBootLoweredModel corrupted = new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(),
                model.basePackage(), declarations, model.artifacts(),
                model.mavenProject(), model.applicationMain());

        GenerationResult result = new SpringBootGenerator().generate(corrupted);

        assertTrue(result instanceof GenerationResult.Failure, result.toString());
        GenerationResult.Failure failure = (GenerationResult.Failure) result;
        assertTrue(failure.diagnostics().stream()
                .anyMatch(d -> d.code().equals("SIR-GEN-INPUT-001")
                        && d.message().contains("responseRepresentation")),
                failure.diagnostics().toString());
    }

}
