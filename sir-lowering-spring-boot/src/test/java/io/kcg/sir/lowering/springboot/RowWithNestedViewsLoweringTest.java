package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Answering with one loaded or created row whose response view nests other views: the related rows are read after
 * the row, one batch read per association, and the return step says how many. A view with nothing nested costs none.
 */
class RowWithNestedViewsLoweringTest {
    private static final String SOURCE = "valid/row-with-nested-views.sir";

    @Test void aReturnedRowDeclaresOneReadPerAssociationItsViewNests() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        // BookDetail nests the author (to one) and the reviews (to many).
        assertEquals(2, returnStep(model, "GetBook").associationStatements());
        assertEquals(2, returnStep(model, "AddBook").associationStatements());
        assertEquals(0, returnStep(model, "GetBookCard").associationStatements());
        assertEquals(List.of(), new SpringBootLoweredIrValidator().validate(model));
    }

    @Test void aReturnThatDoesNotCountTheAssociationsIsRejected() {
        SpringBootLoweredModel damaged = withReturn(LoweringTestSupport.lowerSuccess(SOURCE), "GetBook", 0);
        List<LoweringDiagnostic> diagnostics = new SpringBootLoweredIrValidator().validate(damaged);
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("answering with a row nests 2 batch reads but the return says 0")),
            diagnostics.toString());
        SpringBootLoweredModel overstated = withReturn(LoweringTestSupport.lowerSuccess(SOURCE), "GetBookCard", 1);
        assertTrue(new SpringBootLoweredIrValidator().validate(overstated).stream()
            .anyMatch(d -> d.message().contains("answering with a row nests 0 batch reads but the return says 1")));
    }

    private static SpringBootWorkflow.ReturnStep returnStep(SpringBootLoweredModel model, String capability) {
        return capability(model, capability).workflow().steps().stream()
            .filter(SpringBootWorkflow.ReturnStep.class::isInstance).map(SpringBootWorkflow.ReturnStep.class::cast).findFirst().orElseThrow();
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model, String name) {
        return model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c -> c.javaName().equals(name)).findFirst().orElseThrow();
    }

    private static SpringBootLoweredModel withReturn(SpringBootLoweredModel model, String name, int reads) {
        return new SpringBootLoweredModel(model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
            model.declarations().stream().map(declaration -> declaration instanceof SpringBootDeclaration.CapabilityDeclaration c
                && c.javaName().equals(name) ? withReturn(c, reads) : declaration).toList(),
            model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static SpringBootDeclaration.CapabilityDeclaration withReturn(SpringBootDeclaration.CapabilityDeclaration c, int reads) {
        List<SpringBootWorkflow.Step> steps = c.workflow().steps().stream().map(step -> step instanceof SpringBootWorkflow.ReturnStep r
            ? (SpringBootWorkflow.Step) new SpringBootWorkflow.ReturnStep(r.id(), r.origin(), r.value(), reads) : step).toList();
        return new SpringBootDeclaration.CapabilityDeclaration(c.id(), c.origin(), c.sourceSymbol(), c.javaName(), c.serviceName(),
            c.controllerName(), c.methodName(), c.httpMethod(), c.route(), c.kind(), c.transactionMode(), c.authenticated(), c.actor(),
            c.input(), c.actorBinding(), c.transportPlan(), c.outputType(), c.failures(),
            new SpringBootWorkflow(c.workflow().id(), c.workflow().origin(), c.workflow().variables(), steps));
    }
}
