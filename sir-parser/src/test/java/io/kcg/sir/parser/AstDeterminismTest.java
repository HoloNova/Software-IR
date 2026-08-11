package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstDecimalLiteral;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstUnaryExpression;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AstDeterminismTest {
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void astNodeIdsDoNotDependOnDefaultFormatLocale() {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            List<String> latinIds = workflowStepIds();
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar"));
            assertEquals(latinIds, workflowStepIds());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void negativeConstantsHaveTheSameUnaryAstShapeInEveryContext() {
        ParseResult result = parser.parse(new SirSource(SourceId.of("tests/negative-constants.sir"), negativeSource()));
        assertTrue(result.isSuccess(), result.diagnostics().toString());

        AstEntityDecl entity = (AstEntityDecl) result.document().orElseThrow().software().declarations().get(0);
        AstUnaryExpression constraintValue = assertInstanceOf(
                AstUnaryExpression.class,
                entity.fields().getFirst().constraints().getFirst().arguments().getFirst());

        AstCapabilityDecl capability = (AstCapabilityDecl) result.document().orElseThrow()
                .software().declarations().get(3);
        AstValidateStep validate = (AstValidateStep) capability.workflow().steps().getFirst();
        AstBinaryExpression condition = assertInstanceOf(AstBinaryExpression.class, validate.condition());
        AstUnaryExpression workflowValue = assertInstanceOf(AstUnaryExpression.class, condition.right());

        AstDecimalLiteral constraintOperand = assertInstanceOf(AstDecimalLiteral.class, constraintValue.operand());
        AstDecimalLiteral workflowOperand = assertInstanceOf(AstDecimalLiteral.class, workflowValue.operand());
        assertEquals(new BigDecimal("0.5"), constraintOperand.value());
        assertEquals(constraintOperand.value(), workflowOperand.value());
    }

    private List<String> workflowStepIds() {
        ParseResult result = parser.parse(new SirSource(
                SourceId.of("tests/locale/campus-market.sir"),
                resource("valid/campus-market.sir")));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
        AstCapabilityDecl capability = (AstCapabilityDecl) result.document().orElseThrow()
                .software().declarations().get(5);
        return capability.workflow().steps().stream().map(step -> step.id().value()).toList();
    }

    private static String resource(String path) {
        try (var stream = AstDeterminismTest.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String negativeSource() {
        return """
                sir 0.1
                software NegativeConstants {
                  metadata { displayName "Negative Constants"; namespace "tests.negative"; }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
                  declarations {
                    entity Measure persistent {
                      identity id: Int64 generated auto;
                      field value: Decimal where min(-0.5);
                    }
                    input CheckInput { field value: Decimal; }
                    error Invalid;
                    capability Check {
                      input CheckInput;
                      output Unit;
                      fails Invalid;
                      expose command;
                      workflow {
                        validate input.value >= -0.5 else Invalid;
                        return unit;
                      }
                    }
                  }
                }
                """;
    }
}
