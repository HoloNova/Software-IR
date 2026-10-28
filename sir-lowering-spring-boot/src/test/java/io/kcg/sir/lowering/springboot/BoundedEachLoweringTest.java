package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.model.*;
import org.junit.jupiter.api.Test;

class BoundedEachLoweringTest {
    @Test void constructedCoreCannotReadBodyVariablesAfterTheBlockEnds() {
        var source=LoweringTestSupport.normalized("valid/bounded-each.sir");
        var declarations=source.declarations().stream().map(d -> {
            if(!(d instanceof io.kcg.sir.semantic.model.NormalizedCapability c))return d;
            var each=(io.kcg.sir.semantic.model.NormalizedStep.EachStep)c.workflow().steps().get(1);
            var steps=new java.util.ArrayList<>(c.workflow().steps());steps.add(2,each.body().getFirst());
            return (io.kcg.sir.semantic.model.NormalizedDeclaration)new io.kcg.sir.semantic.model.NormalizedCapability(c.id(),c.name(),c.span(),c.sourceNodeId(),c.actorSymbol(),c.inputSymbol(),c.outputType(),c.fails(),c.requires(),c.exposure(),c.method(),c.path(),
                new io.kcg.sir.semantic.model.NormalizedWorkflow(c.workflow().id(),c.workflow().span(),c.workflow().sourceNodeId(),steps));
        }).toList();
        var damaged=new io.kcg.sir.semantic.api.NormalizedSemanticModel(source.softwareName(),source.metadata(),source.target(),source.symbols(),source.referenceBindings(),source.findItemBindings(),source.expressionTypes(),declarations,source.diagnostics(),source.referenceSiteBindings(),source.expansions());
        var diagnostics=new io.kcg.sir.lowering.springboot.internal.SpringBootInputValidator(damaged).validate();
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("lexical")),diagnostics.toString());
    }

    @Test void constructedTargetCannotReadBodyVariablesAfterTheBlockEnds() {
        var source=LoweringTestSupport.lowerSuccess("valid/bounded-each.sir");
        var declarations=source.declarations().stream().map(d -> {
            if(!(d instanceof SpringBootDeclaration.CapabilityDeclaration c))return d;
            var each=(SpringBootWorkflow.EachStep)c.workflow().steps().get(1);
            var escaped=(SpringBootWorkflow.ValidateStep)each.body().getFirst();
            var steps=new java.util.ArrayList<>(c.workflow().steps());steps.add(2,new SpringBootWorkflow.ValidateStep(
                new io.kcg.sir.lowering.api.LoweredNodeId(escaped.id().value()+"/escape"),escaped.origin(),copyForEscape(escaped.condition()),escaped.errorSymbol()));
            return (SpringBootDeclaration)new SpringBootDeclaration.CapabilityDeclaration(c.id(),c.origin(),c.sourceSymbol(),c.javaName(),c.serviceName(),c.controllerName(),c.methodName(),c.httpMethod(),c.route(),c.kind(),c.transactionMode(),c.authenticated(),c.actor(),c.input(),c.actorBinding(),c.transportPlan(),c.outputType(),c.failures(),
                new SpringBootWorkflow(c.workflow().id(),c.workflow().origin(),c.workflow().variables(),steps),c.requiredInputs());
        }).toList();
        var damaged=new SpringBootLoweredModel(source.irVersion(),source.profile(),source.softwareName(),source.displayName(),source.basePackage(),declarations,source.artifacts(),source.mavenProject(),source.applicationMain(),source.projectArtifacts());
        var diagnostics=new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(damaged);
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("lexical")),diagnostics.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"limit","fetch","method","columns","sourceBudget","bodyBudget","maxBudget","order","orderName","row","itemSame","failure","bodyReturn","outerWrite","transaction","cache"})
    void damagedPlansAreRejectedIndependently(String damage) {
        var model=LoweringTestSupport.lowerSuccess("valid/bounded-each.sir");
        var declarations=model.declarations().stream().map(d -> {
            if(!(d instanceof SpringBootDeclaration.CapabilityDeclaration c))return d;
            var steps=c.workflow().steps().stream().map(s -> {
                if(!(s instanceof SpringBootWorkflow.EachStep e))return s;
                var p=e.plan();var plan=new SpringBootWorkflow.EachPlan(damage.equals("limit")?0:p.limit(),damage.equals("fetch")?100:p.fetchLimit(),
                    damage.equals("method")?"selectCount":p.mapperMethod(),damage.equals("columns")?java.util.List.of():p.columns(),
                    damage.equals("sourceBudget")?0:p.sourceStatements(),damage.equals("bodyBudget")?0:p.bodyStatements(),damage.equals("maxBudget")?0:p.maximumStatements());
                var order=damage.equals("order")?java.util.List.<SpringBootWorkflow.OrderKey>of():damage.equals("orderName")?
                    java.util.List.of(new SpringBootWorkflow.OrderKey(e.orderKeys().getLast().fieldSymbol(),"wrong",false)):e.orderKeys();
                var row=damage.equals("row")?new SpringBootWorkflow.Variable(e.row().symbol(),e.row().targetName(),new LoweredJavaType.ListValue(e.row().type())):e.row();
                var body=new java.util.ArrayList<>(e.body());
                if(damage.equals("bodyReturn"))body.add(c.workflow().steps().getLast());
                if(damage.equals("outerWrite"))body.add(new SpringBootWorkflow.PersistStep(e.id(),e.origin(),((SpringBootWorkflow.LoadStep)c.workflow().steps().getFirst()).result().symbol(),PersistenceAction.UPDATE));
                return (SpringBootWorkflow.Step)new SpringBootWorkflow.EachStep(e.id(),e.origin(),e.entitySymbol(),e.predicate(),order,e.stringMatches(),plan,e.errorSymbol(),row,damage.equals("itemSame")?row:e.itemVariable(),body);
            }).toList();
            return (SpringBootDeclaration)new SpringBootDeclaration.CapabilityDeclaration(c.id(),c.origin(),c.sourceSymbol(),c.javaName(),c.serviceName(),c.controllerName(),c.methodName(),c.httpMethod(),c.route(),c.kind(),
                damage.equals("transaction")?SpringBootDeclaration.TransactionMode.REQUIRED:c.transactionMode(),c.authenticated(),c.actor(),c.input(),c.actorBinding(),c.transportPlan(),c.outputType(),damage.equals("failure")?java.util.List.of():c.failures(),
                new SpringBootWorkflow(c.workflow().id(),c.workflow().origin(),c.workflow().variables(),steps),c.requiredInputs());
        }).toList();
        var artifacts=model.projectArtifacts().stream().map(a -> a instanceof ProjectArtifact.ApplicationConfig config && damage.equals("cache")?
            (ProjectArtifact)new ProjectArtifact.ApplicationConfig(config.id(),config.origin(),config.path(),config.applicationName(),config.rejectUnknownRequestProperties(),false):a).toList();
        var damaged=new SpringBootLoweredModel(model.irVersion(),model.profile(),model.softwareName(),model.displayName(),model.basePackage(),declarations,model.artifacts(),model.mavenProject(),model.applicationMain(),artifacts);
        var diagnostics=new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(damaged);
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().startsWith("each")),diagnostics.toString());
    }

    private static SpringExpression copyForEscape(SpringExpression value) {
        var id=new io.kcg.sir.lowering.api.LoweredNodeId(value.id().value()+"/escape");
        return switch(value) {
            case SpringExpression.NameExpression n -> new SpringExpression.NameExpression(id,n.origin(),n.type(),n.resolvedSymbol(),n.targetName());
            case SpringExpression.MemberExpression m -> new SpringExpression.MemberExpression(id,m.origin(),m.type(),copyForEscape(m.receiver()),m.resolvedMember(),m.targetMember());
            case SpringExpression.BinaryExpression b -> new SpringExpression.BinaryExpression(id,b.origin(),b.type(),copyForEscape(b.left()),b.operator(),copyForEscape(b.right()),b.presentComparison());
            case SpringExpression.IntegerLiteral n -> new SpringExpression.IntegerLiteral(id,n.origin(),n.type(),n.value());
            default -> throw new AssertionError(value);
        };
    }

    @Test void aBoundedSourceHasASentinelAndASeparateBodyNotAnUnboundedFind() {
        var model=LoweringTestSupport.lowerSuccess("valid/bounded-each.sir");
        var cap=model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c -> c.javaName().equals("Restore")).findFirst().orElseThrow();
        assertEquals(SpringBootDeclaration.TransactionMode.READ_COMMITTED,cap.transactionMode());
        var each=assertInstanceOf(SpringBootWorkflow.EachStep.class,cap.workflow().steps().get(1));
        assertEquals(100,each.plan().limit());assertEquals(101,each.plan().fetchLimit());
        assertEquals("selectList",each.plan().mapperMethod());assertEquals(1,each.plan().sourceStatements());
        assertEquals(4,each.body().size());
        assertTrue(each.predicate().toString().contains("removed"),"scope must be preserved");
    }
}
