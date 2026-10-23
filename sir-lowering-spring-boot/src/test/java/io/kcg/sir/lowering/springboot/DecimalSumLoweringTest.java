package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DecimalSumLoweringTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"field", "column", "sql", "method", "budget", "zero", "warnings", "result", "item", "support", "cache", "transaction", "command", "predicate"})
    void malformedAggregatePlansAreRejectedBeforeGeneration(String damage) {
        var model = LoweringTestSupport.lowerSuccess("valid/decimal-sum.sir");
        var declarations = model.declarations().stream().map(declaration -> {
            if (!(declaration instanceof io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration c)
                    || !c.javaName().equals("Total")) return declaration;
            var steps = c.workflow().steps().stream().map(step -> {
                if (!(step instanceof io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.SumStep q)) return step;
                var p = q.plan();
                var plan = new io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.SumPlan(
                        damage.equals("field") ? c.sourceSymbol() : p.fieldSymbol(),
                        damage.equals("column") ? "other" : p.columnName(), damage.equals("sql") ? "SUM(amount); DROP TABLE entry" : p.projectionSql(),
                        damage.equals("method") ? "selectList" : p.mapperMethod(), damage.equals("budget") ? 2 : p.statements(),
                        !damage.equals("zero"), !damage.equals("warnings"));
                var wrong = new io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar(io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind.LONG);
                var result = damage.equals("result") ? new io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable(q.result().symbol(), q.result().targetName(), wrong) : q.result();
                var item = damage.equals("item") ? new io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable(q.itemVariable().symbol(), q.itemVariable().targetName(), wrong) : q.itemVariable();
                return (io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Step) new io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.SumStep(
                        q.id(), q.origin(), q.entitySymbol(), damage.equals("predicate")
                            ? new io.kcg.sir.lowering.springboot.model.SpringExpression.IntegerLiteral(q.predicate().id(),q.predicate().origin(),wrong,java.math.BigInteger.ONE)
                            : q.predicate(), q.stringMatches(), plan, result, item);
            }).toList();
            var workflow = new io.kcg.sir.lowering.springboot.model.SpringBootWorkflow(c.workflow().id(), c.workflow().origin(), c.workflow().variables(), steps);
            return (io.kcg.sir.lowering.springboot.model.SpringBootDeclaration) new io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration(
                    c.id(), c.origin(), c.sourceSymbol(), c.javaName(), c.serviceName(), c.controllerName(), c.methodName(), c.httpMethod(), c.route(), damage.equals("command") ? io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityKind.COMMAND : c.kind(),
                    damage.equals("transaction") ? io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.TransactionMode.NONE : c.transactionMode(),
                    c.authenticated(), c.actor(), c.input(), c.actorBinding(), c.transportPlan(), c.outputType(), c.failures(), workflow);
        }).toList();
        var artifacts = model.projectArtifacts().stream().filter(a -> !damage.equals("support") || !(a instanceof io.kcg.sir.lowering.springboot.model.ProjectArtifact.ExactAggregate))
                .map(a -> damage.equals("cache") && a instanceof io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationConfig config
                    ? (io.kcg.sir.lowering.springboot.model.ProjectArtifact) new io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationConfig(
                        config.id(), config.origin(), config.path(), config.applicationName(), config.rejectUnknownRequestProperties(), false) : a).toList();
        var damaged = new io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel(model.irVersion(), model.profile(), model.softwareName(), model.displayName(),
                model.basePackage(), declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), artifacts);
        assertFalse(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(damaged).isEmpty(), damage);
    }

    @Test void independentlyConstructedCoreCannotSumASecretMeasurement() {
        var model=LoweringTestSupport.normalized("valid/decimal-sum.sir");
        var declarations=model.declarations().stream().map(d -> {
            if(!(d instanceof io.kcg.sir.semantic.model.NormalizedEntity e)||!e.name().equals("Entry"))return d;
            return (io.kcg.sir.semantic.model.NormalizedDeclaration)new io.kcg.sir.semantic.model.NormalizedEntity(e.id(),e.name(),e.span(),e.sourceNodeId(),e.persistent(),
                    e.identity(),e.fields().stream().map(f -> f.name().equals("amount")?f.withSecret(true):f).toList(),e.versionField(),e.credential(),e.scope(),e.uniqueKeys());
        }).toList();
        var damaged=new io.kcg.sir.semantic.api.NormalizedSemanticModel(model.softwareName(),model.metadata(),model.target(),model.symbols(),model.referenceBindings(),
                model.findItemBindings(),model.expressionTypes(),declarations,model.diagnostics(),model.referenceSiteBindings(),model.expansions());
        var diagnostics=new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering().lower(damaged).diagnostics();
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("sum requires a non-secret Decimal")),diagnostics.toString());
    }

    @Test void sumHasAnIndependentExactPlanAndReceivesTheEntityScope() {
        var model = LoweringTestSupport.lowerSuccess("valid/decimal-sum.sir");
        var query = model.declarations().stream()
                .filter(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(c -> c.javaName().equals("Total")).findFirst().orElseThrow().workflow().steps().getFirst();
        assertEquals("SumStep", query.getClass().getSimpleName());
        assertTrue(query.toString().contains("removed"), query.toString());
        assertTrue(model.projectArtifacts().stream().anyMatch(a -> a.getClass().getSimpleName().equals("ExactAggregate")));
    }
}
