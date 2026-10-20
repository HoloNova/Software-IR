package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScalarQueryLoweringTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"budget", "method", "projection", "limit"})
    void damagedPhysicalPlansAreRejected(String damage) {
        var model = LoweringTestSupport.lowerSuccess("valid/scalar-queries.sir");
        var declarations = model.declarations().stream().map(declaration -> {
            if (!(declaration instanceof SpringBootDeclaration.CapabilityDeclaration c) || !c.javaName().equals("Check")) return declaration;
            var steps = c.workflow().steps().stream().map(step -> {
                if (!(step instanceof SpringBootWorkflow.ScalarQueryStep query) || query.plan().kind() != SpringBootWorkflow.ScalarResult.EXISTS) return step;
                var p = query.plan();
                var damaged = new SpringBootWorkflow.ScalarQueryPlan(p.kind(), damage.equals("method") ? "selectList" : p.mapperMethod(),
                    damage.equals("projection") ? java.util.Optional.empty() : p.projectionProperty(),
                    damage.equals("limit") ? java.util.Optional.of("LIMIT 999") : p.tailSql(), damage.equals("budget") ? 2 : p.statements());
                return (SpringBootWorkflow.Step) new SpringBootWorkflow.ScalarQueryStep(query.id(), query.origin(), query.entitySymbol(), query.predicate(), query.stringMatches(), damaged, query.result(), query.itemVariable());
            }).toList();
            var workflow = new SpringBootWorkflow(c.workflow().id(), c.workflow().origin(), c.workflow().variables(), steps);
            return (SpringBootDeclaration) new SpringBootDeclaration.CapabilityDeclaration(c.id(), c.origin(), c.sourceSymbol(), c.javaName(),
                c.serviceName(), c.controllerName(), c.methodName(), c.httpMethod(), c.route(), c.kind(), c.transactionMode(), c.authenticated(),
                c.actor(), c.input(), c.actorBinding(), c.transportPlan(), c.outputType(), c.failures(), workflow);
        }).toList();
        var damaged = new SpringBootLoweredModel(model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
            declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
        assertFalse(new SpringBootLoweredIrValidator().validate(damaged).isEmpty(), damage);
    }

    @Test
    void scalarQueriesDeclareOneStatementAndDifferentResultAndReadPlans() {
        var model = LoweringTestSupport.lowerSuccess("valid/scalar-queries.sir");
        var queries = model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).flatMap(c -> c.workflow().steps().stream())
            .filter(SpringBootWorkflow.ScalarQueryStep.class::isInstance).map(SpringBootWorkflow.ScalarQueryStep.class::cast).toList();
        assertEquals(2, queries.size());
        var exists = queries.getFirst();
        assertEquals("selectObjs", exists.plan().mapperMethod());
        assertEquals("LIMIT 1", exists.plan().tailSql().orElseThrow());
        assertEquals("id", exists.plan().projectionProperty().orElseThrow());
        assertEquals(1, exists.plan().statements());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BOOLEAN), exists.result().type());
        var count = queries.getLast();
        assertEquals("selectCount", count.plan().mapperMethod());
        assertTrue(count.plan().tailSql().isEmpty());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG), count.result().type());
        assertEquals(List.of(), new SpringBootLoweredIrValidator().validate(model));
    }
}
