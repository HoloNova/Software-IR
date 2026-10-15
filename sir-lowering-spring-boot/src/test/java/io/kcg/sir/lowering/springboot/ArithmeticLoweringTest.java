package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Plus, minus and time shifts, lowered: they keep their direction, amount, unit and Java type, and a
 * lowered model that breaks the typing the Java renderer depends on is rejected before generation.
 */
class ArithmeticLoweringTest {
    private static final String SOURCE = "valid/time-arithmetic.sir";

    @Test
    void shiftsAndSumsKeepTheirShapeAndTheirJavaTypes() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        SpringExpression.TimeShiftExpression expires = assertInstanceOf(
                SpringExpression.TimeShiftExpression.class, binding(model, "expiresAt"));
        assertEquals(SpringExpression.ShiftDirection.ADD, expires.direction());
        assertEquals(15L, expires.amount());
        assertEquals(SpringExpression.ShiftUnit.MINUTES, expires.unit());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.INSTANT), expires.type());

        SpringExpression.TimeShiftExpression due = assertInstanceOf(SpringExpression.TimeShiftExpression.class, binding(model, "dueOn"));
        assertEquals(SpringExpression.ShiftUnit.DAYS, due.unit());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LOCAL_DATE), due.type());

        SpringExpression.BinaryExpression attempts = assertInstanceOf(SpringExpression.BinaryExpression.class, binding(model, "attempts"));
        assertEquals(SpringExpression.BinaryOperator.ADD, attempts.operator());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.INTEGER), attempts.type());
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @Test
    void aDateShiftedByHoursIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);

        assertRejected(
                withBinding(model, "dueOn", value -> {
                    SpringExpression.TimeShiftExpression shift = (SpringExpression.TimeShiftExpression) value;
                    return new SpringExpression.TimeShiftExpression(
                            shift.id(), shift.origin(), shift.type(), shift.base(), shift.direction(), shift.amount(),
                            SpringExpression.ShiftUnit.HOURS);
                }),
                "a date can only be shifted by days");
    }

    @Test
    void aShiftOfSomethingThatIsNotADateIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringExpression number = binding(model, "attempts");

        assertRejected(
                withBinding(model, "expiresAt", value -> {
                    SpringExpression.TimeShiftExpression shift = (SpringExpression.TimeShiftExpression) value;
                    return new SpringExpression.TimeShiftExpression(
                            shift.id(), shift.origin(), number.type(), number, shift.direction(), shift.amount(), shift.unit());
                }),
                "a time shift moves a date or a date-time and answers in the same type");
    }

    @Test
    void plusOfTwoDifferentNumericTypesIsRejected() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSuccess(SOURCE);
        SpringExpression.DecimalLiteral decimal = new SpringExpression.DecimalLiteral(
                binding(model, "attempts").id(), binding(model, "attempts").origin(),
                new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BIG_DECIMAL), BigDecimal.ONE);

        assertRejected(
                withBinding(model, "attempts", value -> {
                    SpringExpression.BinaryExpression sum = (SpringExpression.BinaryExpression) value;
                    return new SpringExpression.BinaryExpression(sum.id(), sum.origin(), sum.type(), sum.left(), sum.operator(), decimal);
                }),
                "arithmetic needs two operands of the result's numeric type");
    }

    private static SpringExpression binding(SpringBootLoweredModel model, String property) {
        return create(model).bindings().stream()
                .filter(value -> value.targetProperty().equals(property))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static SpringBootWorkflow.CreateStep create(SpringBootLoweredModel model) {
        return capability(model).workflow().steps().stream()
                .filter(SpringBootWorkflow.CreateStep.class::isInstance)
                .map(SpringBootWorkflow.CreateStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(SpringBootLoweredModel model) {
        return model.declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .filter(candidate -> candidate.javaName().equals("OpenTicket"))
                .findFirst()
                .orElseThrow();
    }

    private static SpringBootLoweredModel withBinding(
            SpringBootLoweredModel model, String property, UnaryOperator<SpringExpression> change) {
        SpringBootDeclaration.CapabilityDeclaration capability = capability(model);
        List<SpringBootWorkflow.Step> steps = capability.workflow().steps().stream()
                .map(step -> step instanceof SpringBootWorkflow.CreateStep create
                        ? (SpringBootWorkflow.Step) new SpringBootWorkflow.CreateStep(
                                create.id(), create.origin(), create.entitySymbol(), create.result(),
                                create.bindings().stream()
                                        .map(binding -> binding.targetProperty().equals(property)
                                                ? new SpringBootWorkflow.Binding(
                                                        binding.fieldSymbol(), binding.targetProperty(),
                                                        change.apply(binding.value()), binding.sourcePropertyName())
                                                : binding)
                                        .toList())
                        : step)
                .toList();
        SpringBootWorkflow workflow = new SpringBootWorkflow(
                capability.workflow().id(), capability.workflow().origin(), capability.workflow().variables(), steps);
        SpringBootDeclaration.CapabilityDeclaration damaged = new SpringBootDeclaration.CapabilityDeclaration(
                capability.id(), capability.origin(), capability.sourceSymbol(), capability.javaName(),
                capability.serviceName(), capability.controllerName(), capability.methodName(), capability.httpMethod(),
                capability.route(), capability.kind(), capability.transactionMode(), capability.authenticated(),
                capability.actor(), capability.input(), capability.actorBinding(), capability.transportPlan(),
                capability.outputType(), capability.failures(), workflow);
        List<SpringBootDeclaration> declarations = model.declarations().stream()
                .map(declaration -> declaration == capability ? (SpringBootDeclaration) damaged : declaration)
                .toList();
        return new SpringBootLoweredModel(
                model.irVersion(), model.profile(), model.softwareName(), model.displayName(), model.basePackage(),
                declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static void assertRejected(SpringBootLoweredModel model, String messageFragment) {
        List<LoweringDiagnostic> diagnostics = new SpringBootLoweredIrValidator().validate(model);
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains(messageFragment)),
                () -> "expected a diagnostic containing '" + messageFragment + "' but got " + diagnostics);
    }
}
