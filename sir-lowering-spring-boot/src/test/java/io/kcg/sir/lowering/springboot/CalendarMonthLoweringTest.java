package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarMonthLoweringTest {
    @ParameterizedTest
    @ValueSource(strings={"minimum","maximum","budget","sameResult","wrongResult","transaction","command","failure","support","binding"})
    void damagedTargetPlansAreRejectedBeforeRendering(String damage) {
        var model=LoweringTestSupport.lowerSuccess("valid/calendar-month.sir");
        var declarations=model.declarations().stream().map(d -> {
            if(!(d instanceof SpringBootDeclaration.CapabilityDeclaration c))return d;
            var steps=c.workflow().steps().stream().map(s -> {
                if(!(s instanceof SpringBootWorkflow.MonthStep m))return s;
                var plan=new SpringBootWorkflow.MonthPlan(damage.equals("minimum")?"0000-01-01T00:00:00Z":m.plan().minimumInstant(),
                    damage.equals("maximum")?"+10000-01-01T00:00:00Z":m.plan().maximumInstant(),damage.equals("budget")?1:0);
                var start=damage.equals("wrongResult")?new SpringBootWorkflow.Variable(m.since().symbol(),m.since().targetName(),new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.STRING)):m.since();
                return (SpringBootWorkflow.Step)new SpringBootWorkflow.MonthStep(m.id(),m.origin(),m.month(),m.zone(),start,
                    damage.equals("sameResult")?start:m.until(),m.errorSymbol(),plan);
            }).toList();
            var flow=new SpringBootWorkflow(c.workflow().id(),c.workflow().origin(),c.workflow().variables(),steps);
            return (SpringBootDeclaration)new SpringBootDeclaration.CapabilityDeclaration(c.id(),c.origin(),c.sourceSymbol(),c.javaName(),c.serviceName(),c.controllerName(),c.methodName(),c.httpMethod(),c.route(),
                damage.equals("command")?SpringBootDeclaration.CapabilityKind.COMMAND:c.kind(),
                damage.equals("transaction")?SpringBootDeclaration.TransactionMode.READ_ONLY:c.transactionMode(),c.authenticated(),c.actor(),c.input(),c.actorBinding(),c.transportPlan(),c.outputType(),
                damage.equals("failure")?java.util.List.of():c.failures(),flow,c.requiredInputs());
        }).toList();
        var artifacts=model.projectArtifacts().stream().map(a -> a instanceof ProjectArtifact.ValidationSupport v && (damage.equals("support") || damage.equals("binding"))
            ?(ProjectArtifact)new ProjectArtifact.ValidationSupport(v.id(),v.origin(),v.path(),v.packageName(),v.simpleName(),v.fieldErrorSimpleName(),v.constraintCodes().stream().filter(x -> !damage.equals("support") || !x.equals("timeZone")).toList()):a).toList();
        var damaged=new SpringBootLoweredModel(model.irVersion(),model.profile(),model.softwareName(),model.displayName(),model.basePackage(),declarations,model.artifacts(),model.mavenProject(),model.applicationMain(),artifacts);
        assertFalse(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(damaged).isEmpty(),damage);
    }

    @ParameterizedTest @ValueSource(strings={"results","readonly","failure","secret"})
    void independentlyConstructedCoreCannotRelaxMonthSemantics(String damage) {
        var source=LoweringTestSupport.normalized("valid/calendar-month.sir");
        var declarations=source.declarations().stream().map(d -> {
            if(d instanceof io.kcg.sir.semantic.model.NormalizedInput i && damage.equals("secret"))
                return (io.kcg.sir.semantic.model.NormalizedDeclaration)new io.kcg.sir.semantic.model.NormalizedInput(i.id(),i.name(),i.span(),i.sourceNodeId(),i.kind(),i.patchSourceEntity(),
                    i.fields().stream().map(f -> f.name().equals("zone")?f.withSecret(true):f).toList());
            if(!(d instanceof io.kcg.sir.semantic.model.NormalizedCapability c))return d;
            var steps=c.workflow().steps().stream().map(s -> s instanceof io.kcg.sir.semantic.model.NormalizedStep.MonthStep m && damage.equals("results")
                ?(io.kcg.sir.semantic.model.NormalizedStep)new io.kcg.sir.semantic.model.NormalizedStep.MonthStep(m.sourceNodeId(),m.span(),m.month(),m.zone(),m.sinceVariable(),m.sinceVariable(),m.errorSymbol()):s).toList();
            var flow=new io.kcg.sir.semantic.model.NormalizedWorkflow(c.workflow().id(),c.workflow().span(),c.workflow().sourceNodeId(),steps);
            return (io.kcg.sir.semantic.model.NormalizedDeclaration)new io.kcg.sir.semantic.model.NormalizedCapability(c.id(),c.name(),c.span(),c.sourceNodeId(),c.actorSymbol(),c.inputSymbol(),c.outputType(),
                damage.equals("failure")?java.util.List.of():c.fails(),damage.equals("readonly")?java.util.List.of():c.requires(),c.exposure(),c.method(),c.path(),flow);
        }).toList();
        var damaged=new io.kcg.sir.semantic.api.NormalizedSemanticModel(source.softwareName(),source.metadata(),source.target(),source.symbols(),source.referenceBindings(),source.findItemBindings(),source.expressionTypes(),declarations,source.diagnostics(),source.referenceSiteBindings(),source.expansions());
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering().lower(damaged).diagnostics().stream().anyMatch(d -> d.severity().isError()),damage);
    }
}
