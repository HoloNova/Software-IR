package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.RequiredArithmeticInput;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MultiplicationLoweringTest {
    @Test void conversionsAreExplicitAndInputChecksAreBoundToTheirRealFields() {
        var model = LoweringTestSupport.lowerSuccess("valid/multiplication.sir");
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
        var capability = capability(model);
        var multiplication = multiplication(capability);
        var conversion = assertInstanceOf(SpringExpression.IntegralToDecimal.class, multiplication.right());
        assertEquals(multiplication.type(), conversion.type());
        assertEquals(LoweredJavaType.ScalarKind.INTEGER,
                assertInstanceOf(LoweredJavaType.Scalar.class, conversion.operand().type()).kind());
        assertNotEquals(conversion.id(), conversion.operand().id());
        assertEquals(conversion.origin(), conversion.operand().origin());
        assertEquals(List.of("price", "quantity", "left", "right", "wide"),
                capability.arithmeticInputs().stream().map(RequiredArithmeticInput::fieldPath).toList());
        assertTrue(capability.arithmeticInputs().stream().allMatch(plan ->
                plan.inputSymbol().equals(capability.input().orElseThrow().symbol())));
    }

    @Test void referenceIdentityArithmeticUsesTheDtoFieldPath() {
        String source = LoweringTestSupport.source("valid/multiplication.sir").replace("    capability Multiply {", """
                    input Reference { field product: Ref<Product>; }
                    capability ReferenceMath {
                      input Reference; output Unit; fails Missing; requires readonly; expose query;
                      workflow { validate input.product.id * 2 > 0 else Missing; return unit; }
                    }
                """ + "    capability Multiply {");
        var model = LoweringTestSupport.lowerSourceSuccess(source);
        var capability = model.declarations().stream().filter(CapabilityDeclaration.class::isInstance)
                .map(CapabilityDeclaration.class::cast).filter(cap -> cap.javaName().equals("ReferenceMath")).findFirst().orElseThrow();
        var check = capability.arithmeticInputs().getFirst();
        assertEquals("productId", check.propertyName());
        assertEquals("productId", check.fieldPath());
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "reordered", "wrong_property", "wrong_owner", "wrong_path", "duplicate"})
    void malformedRequiredInputPlansAreRejectedIndependently(String mutation) {
        var model = LoweringTestSupport.lowerSuccess("valid/multiplication.sir");
        var original = capability(model);
        var checks = original.arithmeticInputs();
        var first = checks.getFirst();
        List<RequiredArithmeticInput> changed = switch (mutation) {
            case "missing" -> checks.subList(1, checks.size());
            case "reordered" -> Stream.concat(Stream.of(checks.get(1), first), checks.stream().skip(2)).toList();
            case "duplicate" -> Stream.concat(checks.stream(), Stream.of(first)).toList();
            default -> Stream.concat(Stream.of(new RequiredArithmeticInput(first.id(), first.origin(),
                    mutation.equals("wrong_owner") ? original.sourceSymbol() : first.inputSymbol(), first.fieldSymbol(),
                    mutation.equals("wrong_property") ? "other" : first.propertyName(),
                    mutation.equals("wrong_path") ? "other" : first.fieldPath())), checks.stream().skip(1)).toList();
        };
        var diagnostics = new SpringBootLoweredIrValidator().validate(replace(model, cap -> copy(cap, cap.workflow(), changed)));
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("arithmetic input")), diagnostics.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"decimal_operand", "optional_operand", "non_decimal_result"})
    void malformedConversionsCannotBeSmuggledIntoAnOtherwiseValidModel(String mutation) {
        var model = LoweringTestSupport.lowerSuccess("valid/multiplication.sir");
        var binary = multiplication(capability(model));
        var conversion = (SpringExpression.IntegralToDecimal) binary.right();
        var member = (SpringExpression.MemberExpression) conversion.operand();
        SpringExpression operand = switch (mutation) {
            case "decimal_operand" -> new SpringExpression.DecimalLiteral(member.id(), member.origin(), conversion.type(), BigDecimal.ONE);
            case "optional_operand" -> new SpringExpression.MemberExpression(member.id(), member.origin(),
                    new LoweredJavaType.OptionalValue(member.type()), member.receiver(), member.resolvedMember(), member.targetMember());
            default -> member;
        };
        var type = mutation.equals("non_decimal_result") ? member.type() : conversion.type();
        var invalid = new SpringExpression.IntegralToDecimal(conversion.id(), conversion.origin(), type, operand);
        var expression = new SpringExpression.BinaryExpression(binary.id(), binary.origin(), binary.type(),
                binary.left(), binary.operator(), invalid, binary.presentComparison());
        var damaged = replace(model, cap -> {
            var create = (SpringBootWorkflow.CreateStep) cap.workflow().steps().getFirst();
            var binding = create.bindings().getFirst();
            var bindings = Stream.concat(Stream.of(new SpringBootWorkflow.Binding(binding.fieldSymbol(), binding.targetProperty(),
                    expression, binding.sourcePropertyName(), binding.relative())), create.bindings().stream().skip(1)).toList();
            var changed = new SpringBootWorkflow.CreateStep(create.id(), create.origin(), create.entitySymbol(), create.result(), bindings);
            var workflow = new SpringBootWorkflow(cap.workflow().id(), cap.workflow().origin(), cap.workflow().variables(),
                    Stream.concat(Stream.of(changed), cap.workflow().steps().stream().skip(1)).toList());
            return copy(cap, workflow, cap.arithmeticInputs());
        });
        var diagnostics = new SpringBootLoweredIrValidator().validate(damaged);
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("integral-to-decimal")), diagnostics.toString());
    }

    @Test void patchIdentityIsAnOrdinaryEnvelopeValueNotAnAbsentChange() {
        var model = LoweringTestSupport.lowerSourceSuccess(patchSource("input.id", "validate input.id * 1 > 0 else Conflict;"));
        var capability = model.declarations().stream().filter(CapabilityDeclaration.class::isInstance)
                .map(CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("id"), capability.arithmeticInputs().stream().map(RequiredArithmeticInput::fieldPath).toList());
    }

    @Test void patchChangesDoNotBecomeRequiredOrLoseTheirAbsenceSemantics() {
        var failure = assertThrows(AssertionError.class, () -> LoweringTestSupport.lowerSourceSuccess(
                patchSource("input.id", "validate input.balance * 1 == 0.0 else Conflict;")));
        assertTrue(failure.getMessage().startsWith("lowering failed:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("PATCH changes"), failure.getMessage());
    }

    private static String patchSource(String identity, String check) {
        return """
                sir 0.3
                software PatchArithmetic {
                  metadata { displayName "Patch Arithmetic"; namespace "example.patch"; }
                  target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
                  sources {} imports {}
                  declarations {
                    entity Wallet persistent { identity id: Id generated auto; field balance: Money; field version: Version; }
                    input Change patch of Wallet { field id: Id; field balance: Money; }
                    error Missing http 404; error Conflict http 409;
                    capability PatchWallet {
                      input Change; output Unit; fails Conflict; fails Missing; requires atomic; expose command;
                      workflow {
                        %s
                        load Wallet by %s as wallet else Missing;
                        update wallet { balance: input.balance; }
                        persist wallet else Conflict;
                        return unit;
                      }
                    }
                  }
                }
                """.formatted(check, identity);
    }

    private static CapabilityDeclaration capability(SpringBootLoweredModel model) {
        return model.declarations().stream().filter(CapabilityDeclaration.class::isInstance).map(CapabilityDeclaration.class::cast)
                .filter(cap -> cap.javaName().equals("Multiply")).findFirst().orElseThrow();
    }

    private static SpringExpression.BinaryExpression multiplication(CapabilityDeclaration cap) {
        return (SpringExpression.BinaryExpression) ((SpringBootWorkflow.CreateStep) cap.workflow().steps().getFirst()).bindings().getFirst().value();
    }

    private static SpringBootLoweredModel replace(SpringBootLoweredModel model, UnaryOperator<CapabilityDeclaration> replacement) {
        List<SpringBootDeclaration> declarations = model.declarations().stream().map(decl ->
                decl instanceof CapabilityDeclaration cap && cap.javaName().equals("Multiply") ? replacement.apply(cap) : decl).toList();
        return new SpringBootLoweredModel(model.irVersion(), model.profile(), model.softwareName(), model.displayName(),
                model.basePackage(), declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
    }

    private static CapabilityDeclaration copy(CapabilityDeclaration cap, SpringBootWorkflow workflow, List<RequiredArithmeticInput> inputs) {
        return new CapabilityDeclaration(cap.id(), cap.origin(), cap.sourceSymbol(), cap.javaName(), cap.serviceName(), cap.controllerName(),
                cap.methodName(), cap.httpMethod(), cap.route(), cap.kind(), cap.transactionMode(), cap.authenticated(), cap.actor(),
                cap.input(), cap.actorBinding(), cap.transportPlan(), cap.outputType(), cap.failures(), workflow, inputs);
    }
}
