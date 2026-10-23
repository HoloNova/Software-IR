package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.model.*;
import org.junit.jupiter.api.Test;

class GroupedSumLoweringTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"field","key","column","keyColumn","countSql","dataSql","countMethod","dataMethod","budget","warnings","result","item","predicate","transaction","cache","support","pageType"})
    void constructedGroupPlansCannotBypassTheirContract(String damage) {
        var model=LoweringTestSupport.lowerSuccess("valid/decimal-group.sir");
        var declarations=model.declarations().stream().map(d -> {
            if (!(d instanceof SpringBootDeclaration.CapabilityDeclaration c)) return d;
            var steps=c.workflow().steps().stream().map(step -> {
                if (!(step instanceof SpringBootWorkflow.GroupedSumStep g)) return step;
                var p=g.plan();
                var plan=new SpringBootWorkflow.GroupedSumPlan(
                    damage.equals("field")?c.sourceSymbol():p.fieldSymbol(), damage.equals("key")?p.fieldSymbol():p.keySymbol(),
                    damage.equals("column")?"other":p.columnName(),damage.equals("keyColumn")?"owner_id":p.keyColumn(),
                    damage.equals("countSql")?"*":p.countProjection(),damage.equals("dataSql")?"amount AS g_total":p.dataProjection(),
                    damage.equals("countMethod")?"selectObjs":p.countMethod(),damage.equals("dataMethod")?"selectList":p.dataMethod(),
                    damage.equals("budget")?1:p.statements(),!damage.equals("warnings"));
                var wrong=new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG);
                var result=damage.equals("result")?new SpringBootWorkflow.Variable(g.result().symbol(),g.result().targetName(),wrong):g.result();
                var item=damage.equals("item")?new SpringBootWorkflow.Variable(g.itemVariable().symbol(),g.itemVariable().targetName(),wrong):g.itemVariable();
                var predicate=damage.equals("predicate")?new SpringExpression.IntegerLiteral(g.predicate().id(),g.predicate().origin(),wrong,java.math.BigInteger.ONE):g.predicate();
                return (SpringBootWorkflow.Step)new SpringBootWorkflow.GroupedSumStep(g.id(),g.origin(),g.entitySymbol(),predicate,g.stringMatches(),plan,g.page(),result,item);
            }).toList();
            var flow=new SpringBootWorkflow(c.workflow().id(),c.workflow().origin(),c.workflow().variables(),steps);
            return (SpringBootDeclaration)new SpringBootDeclaration.CapabilityDeclaration(c.id(),c.origin(),c.sourceSymbol(),c.javaName(),c.serviceName(),c.controllerName(),c.methodName(),c.httpMethod(),c.route(),c.kind(),
                damage.equals("transaction")?SpringBootDeclaration.TransactionMode.READ_ONLY:c.transactionMode(),c.authenticated(),c.actor(),c.input(),c.actorBinding(),c.transportPlan(),c.outputType(),c.failures(),flow);
        }).toList();
        var artifacts=model.projectArtifacts().stream().filter(a -> !damage.equals("support") || !(a instanceof ProjectArtifact.ExactAggregate))
            .map(a -> {
                if (damage.equals("cache") && a instanceof ProjectArtifact.ApplicationConfig c)
                    return (ProjectArtifact)new ProjectArtifact.ApplicationConfig(c.id(),c.origin(),c.path(),c.applicationName(),c.rejectUnknownRequestProperties(),false);
                if (damage.equals("pageType") && a instanceof ProjectArtifact.ExactAggregate c)
                    return (ProjectArtifact)new ProjectArtifact.ExactAggregate(c.id(),c.origin(),c.path(),c.packageName(),c.simpleName(),java.util.Optional.of("malformed.Type"));
                return a;
            }).toList();
        var damaged=new SpringBootLoweredModel(model.irVersion(),model.profile(),model.softwareName(),model.displayName(),model.basePackage(),declarations,model.artifacts(),model.mavenProject(),model.applicationMain(),artifacts);
        assertFalse(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(damaged).isEmpty(),damage);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"secret","optional","storedGroup"})
    void independentlyConstructedCoreCannotWeakenSourceKeyOrCarrierRules(String damage) {
        var source=LoweringTestSupport.normalized("valid/decimal-group.sir");
        var declarations=source.declarations().stream().map(d -> {
            if (!(d instanceof io.kcg.sir.semantic.model.NormalizedEntity e)||!e.name().equals("Entry"))return d;
            var fields=e.fields().stream().map(f -> {
                if(!f.name().equals("category"))return f;
                if(damage.equals("secret"))return f.withSecret(true);
                var type=damage.equals("optional")?new io.kcg.sir.semantic.type.OptionalType(f.type())
                    :new io.kcg.sir.semantic.type.SumGroupType((io.kcg.sir.semantic.type.RefType)f.type());
                return new io.kcg.sir.semantic.model.NormalizedField(f.id(),f.name(),f.span(),f.sourceNodeId(),type,f.constraints());
            }).toList();
            return (io.kcg.sir.semantic.model.NormalizedDeclaration)new io.kcg.sir.semantic.model.NormalizedEntity(e.id(),e.name(),e.span(),e.sourceNodeId(),e.persistent(),
                e.identity(),fields,e.versionField(),e.credential(),e.scope(),e.uniqueKeys());
        }).toList();
        var damaged=new io.kcg.sir.semantic.api.NormalizedSemanticModel(source.softwareName(),source.metadata(),source.target(),source.symbols(),source.referenceBindings(),source.findItemBindings(),source.expressionTypes(),declarations,source.diagnostics(),source.referenceSiteBindings(),source.expansions());
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering().lower(damaged).diagnostics().stream().anyMatch(d -> d.severity().isError()));
    }

    @Test void groupedCountAndPageAreAnExplicitBoundedSnapshotPlan() {
        var model = LoweringTestSupport.lowerSuccess("valid/decimal-group.sir");
        var capability = model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        var query = capability.workflow().steps().stream().filter(SpringBootWorkflow.GroupedSumStep.class::isInstance)
            .map(SpringBootWorkflow.GroupedSumStep.class::cast).findFirst().orElseThrow();
        assertEquals(SpringBootDeclaration.TransactionMode.REPEATABLE_READ, capability.transactionMode());
        assertEquals("DISTINCT `category_id`", query.plan().countProjection());
        assertEquals("`category_id` AS g_key,COALESCE(SUM(`amount`), 0) AS g_total", query.plan().dataProjection());
        assertEquals(2, query.plan().statements());
        assertTrue(query.plan().rejectWarnings());
        assertTrue(query.predicate().toString().contains("category_rel.id = entry.category_id"), query.predicate().toString());
        assertTrue(query.predicate().toString().contains("removed"), query.predicate().toString());
        assertTrue(model.projectArtifacts().stream().anyMatch(ProjectArtifact.ExactAggregate.class::isInstance));
    }
}
