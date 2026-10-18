package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for the {@code secret} field modifier and the {@code matches} operator.
 *
 * <p>Both words are contextual: they mean something only in their one position, so a field or a name
 * spelled that way elsewhere stays legal. Whether a well-formed use is allowed (which types may be
 * secret, what may be matched) is a later phase's rule.
 */
class SecretFieldGrammarTest {
    private static final String ACCOUNT = """
            entity Account persistent {
              identity id: Id generated auto;
              field name: Text unique;
              field passwordHash: Text secret;
              field token: Text unique secret @id("token");
              field secret: Text;
              field matches: Text;
            }
            error Bad;
            input Login { field name: Text; field password: Text; }
            """;

    private final SirParser parser = new DefaultSirParser();

    @Test
    void secretIsCarriedOnTheEntityFieldThatDeclaresIt() {
        AstSoftware software = parseOk(ACCOUNT);

        assertTrue(entityField(software, "passwordHash").secret());
        assertTrue(entityField(software, "token").secret(), "after unique, before the declared id");
        assertTrue(entityField(software, "token").unique());
        assertFalse(entityField(software, "name").secret());
    }

    @Test
    void secretAndMatchesAreStillOrdinaryIdentifiersWhereTheyAreNotTheModifierOrOperator() {
        AstSoftware software = parseOk(ACCOUNT);

        assertFalse(entityField(software, "secret").secret(), "a field called secret is not a secret field");
        assertFalse(entityField(software, "matches").secret());
    }

    @Test
    void secretIsNotAdmittedOnInputOrViewFields() {
        assertRejected(ACCOUNT + "input Bad2 { field password: Text secret; }");
        assertRejected(ACCOUNT + "view Bad3 from Account { field name: Text secret; }");
    }

    @Test
    void matchesIsABinaryOperatorBetweenTwoValues() {
        AstBinaryExpression matched = condition(parseOk(ACCOUNT + capability("validate account.passwordHash matches input.password else Bad;")));

        assertEquals(AstBinaryOperator.MATCHES, matched.operator());
    }

    @Test
    void matchesBindsLooserThanArithmeticAndTighterThanEquality() {
        AstBinaryExpression equality = condition(parseOk(ACCOUNT + capability(
                "validate account.passwordHash matches input.password == true else Bad;")));

        assertEquals(AstBinaryOperator.EQ, equality.operator());
        assertEquals(AstBinaryOperator.MATCHES, ((AstBinaryExpression) equality.left()).operator());
    }

    @Test
    void matchesNeedsAnOperandOnEachSide() {
        assertRejected(ACCOUNT + capability("validate account.passwordHash matches else Bad;"));
        assertRejected(ACCOUNT + capability("validate matches input.password else Bad;"));
    }

    private static String capability(String step) {
        return """
                capability Login {
                  input Login;
                  output Account;
                  fails Bad;
                  requires readonly;
                  expose query;
                  workflow {
                    load Account by name: input.name as account else Bad;
                    %s
                    return account;
                  }
                }
                """.formatted(step);
    }

    private static AstBinaryExpression condition(AstSoftware software) {
        AstCapabilityDecl capability = (AstCapabilityDecl) software.declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        return (AstBinaryExpression) ((AstValidateStep) capability.workflow().steps().get(1)).condition();
    }

    private static AstField entityField(AstSoftware software, String fieldName) {
        AstEntityDecl entity = (AstEntityDecl) software.declarations().stream()
                .filter(AstEntityDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        return entity.fields().stream()
                .filter(value -> value.name().text().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    private AstSoftware parseOk(String declarations) {
        SourceUnitParseResult result = parse(declarations);

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        return result.unit().map(ParseTestSupport::document).orElseThrow().software();
    }

    private void assertRejected(String declarations) {
        SourceUnitParseResult result = parse(declarations);
        String diagnostics = messages(result.diagnostics());

        assertFalse(result.isSuccess(), () -> diagnostics);
        assertTrue(
                result.diagnostics().stream().map(Diagnostic::code).anyMatch(code -> code.value().startsWith("SIR-PARSE-")),
                () -> diagnostics);
    }

    private SourceUnitParseResult parse(String declarations) {
        return parser.parseProject(new SirSource(SourceId.of("tests/secret.sir"), document(declarations)));
    }

    private static String document(String declarations) {
        return """
                sir 0.3
                software SecretGrammar {
                  metadata {
                    displayName "Secret Grammar";
                    namespace "tests.grammar.secret";
                  }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
                  sources { }
                  imports { }
                  declarations {
                    %s
                  }
                }
                """.formatted(declarations);
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(value -> value.code().value() + ": " + value.message())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }
}
