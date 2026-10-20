package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.*;
import org.junit.jupiter.api.Test;

class OptionalValueLoweringTest {
    @Test
    void optionalComparisonsCarryAnExplicitPresentOnlyPlan() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess("valid/optional-values.sir");
        SpringBootDeclaration.CapabilityDeclaration check = model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals("Check")).findFirst().orElseThrow();
        SpringExpression.BinaryExpression comparison = (SpringExpression.BinaryExpression) check.workflow().steps().stream()
                .filter(SpringBootWorkflow.ValidateStep.class::isInstance)
                .map(SpringBootWorkflow.ValidateStep.class::cast).findFirst().orElseThrow().condition();
        assertTrue(comparison.presentComparison().isPresent());
        var read = assertInstanceOf(SpringExpression.OptionalPayloadValue.class, comparison.right());
        assertInstanceOf(SpringExpression.MemberExpression.class, comparison.left());
        var raw = new SpringExpression.BinaryExpression(comparison.id(), comparison.origin(), comparison.type(),
                comparison.left(), comparison.operator(), read.payloadMember(), comparison.presentComparison());
        assertTrue(new SpringBootLoweredIrValidator().validate(replaceComparison(model, check, raw)).stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("explicit nullable read plan")));
        var wrongCarrier = new SpringExpression.OptionalPayloadValue(read.id(), read.origin(), read.type(), comparison.left());
        var damaged = new SpringExpression.BinaryExpression(comparison.id(), comparison.origin(), comparison.type(),
                comparison.left(), comparison.operator(), wrongCarrier, comparison.presentComparison());
        assertTrue(new SpringBootLoweredIrValidator().validate(replaceComparison(model, check, damaged)).stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("typed INPUT member")));
        SpringExpression.PresentComparison plan = comparison.presentComparison().orElseThrow();
        assertTrue(plan.checkLeft());
        assertTrue(plan.checkRight());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.INTEGER), plan.operandType());
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
        SpringExpression.BinaryExpression missing = new SpringExpression.BinaryExpression(comparison.id(), comparison.origin(),
                comparison.type(), comparison.left(), comparison.operator(), comparison.right());
        assertTrue(new SpringBootLoweredIrValidator().validate(replaceComparison(model, check, missing)).stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("present-only comparison plan")));
        SpringExpression.BinaryExpression wrong = new SpringExpression.BinaryExpression(comparison.id(), comparison.origin(),
                comparison.type(), comparison.left(), comparison.operator(), comparison.right(),
                java.util.Optional.of(new SpringExpression.PresentComparison(plan.operandType(), false, true)));
        assertTrue(new SpringBootLoweredIrValidator().validate(replaceComparison(model, check, wrong)).stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("does not match its operands")));
    }

    @Test
    void existingCorrelatedQueriesKeepTheSameNullRulesInsideNot() {
        String source = LoweringTestSupport.source("valid/optional-values.sir")
                .replace("    view EntryView", "    entity Child persistent { identity id: Id generated auto; field entry: Ref<Entry>; field score: Optional<Count>; }\n    view EntryView")
                .replace("(input.minimum is none or item.score >= input.minimum)\n          and not (item.score == input.minimum) and item.dueAt is present",
                        "any(Child, entry == item and (score is none or not (score == 1)))");
        SpringBootLoweredModel model = LoweringTestSupport.lowerSourceSuccess(source);
        SpringExpression.ExistsPredicate exists = model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .flatMap(capability -> capability.workflow().steps().stream())
                .filter(SpringBootWorkflow.FindStep.class::isInstance).map(SpringBootWorkflow.FindStep.class::cast)
                .map(SpringBootWorkflow.FindStep::predicate).map(SpringExpression.ExistsPredicate.class::cast).findFirst().orElseThrow();
        assertTrue(exists.subquerySql().contains("child_rel.score IS NULL"), exists.subquerySql());
        assertTrue(exists.subquerySql().contains("NOT (COALESCE(child_rel.score = {0}, FALSE))"), exists.subquerySql());
    }

    private static SpringBootLoweredModel replaceComparison(SpringBootLoweredModel model,
            SpringBootDeclaration.CapabilityDeclaration capability, SpringExpression.BinaryExpression replacement) {
        var steps = capability.workflow().steps().stream().map(step -> {
            if (step instanceof SpringBootWorkflow.ValidateStep validate && validate.condition().id().equals(replacement.id())) {
                return (SpringBootWorkflow.Step) new SpringBootWorkflow.ValidateStep(validate.id(), validate.origin(), replacement, validate.errorSymbol());
            }
            return step;
        }).toList();
        var workflow = new SpringBootWorkflow(capability.workflow().id(), capability.workflow().origin(), capability.workflow().variables(), steps);
        var changed = new SpringBootDeclaration.CapabilityDeclaration(capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                capability.serviceName(), capability.controllerName(), capability.methodName(), capability.httpMethod(), capability.route(), capability.kind(),
                capability.transactionMode(), capability.authenticated(), capability.actor(), capability.input(), capability.actorBinding(), capability.transportPlan(),
                capability.outputType(), capability.failures(), workflow);
        return new SpringBootLoweredModel(model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                model.declarations().stream().map(declaration -> declaration == capability ? (SpringBootDeclaration) changed : declaration).toList(),
                model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }
}
