package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstUpdateStep;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for the guard of an update: {@code update row when <condition> else Error { ... }}.
 *
 * <p>The negative cases assert rejection by the grammar itself; whether a well-formed guard is legal
 * (the entity, the shape of the condition, the bindings) is a later phase's rule.
 */
class GuardedUpdateGrammarTest {
    private final SirParser parser = new DefaultSirParser();

    @Test
    void aGuardIsAConditionAndTheErrorRaisedWhenTheStoredRowDoesNotMeetIt() {
        AstUpdateStep update = update("update old when old.used == false else Invalid { used: true; }");

        AstBinaryExpression condition = assertInstanceOf(AstBinaryExpression.class, update.guard().orElseThrow());
        assertEquals(AstBinaryOperator.EQ, condition.operator());
        assertEquals("Invalid", update.guardError().orElseThrow().text());
        assertEquals("old", update.target().text());
        assertEquals(1, update.bindings().size());
    }

    @Test
    void anUpdateWithoutAGuardHasNoGuardAndNoError() {
        AstUpdateStep update = update("update old { used: true; }");

        assertTrue(update.guard().isEmpty());
        assertTrue(update.guardError().isEmpty());
    }

    @Test
    void theGuardMayCombineConditionsAndUseAValueComputedFromTheClock() {
        AstUpdateStep update = update(
                "update old when old.used == false and old.expiresAt > now() + 5 minutes else Invalid { used: true; }");

        AstBinaryExpression both = assertInstanceOf(AstBinaryExpression.class, update.guard().orElseThrow());
        assertEquals(AstBinaryOperator.AND, both.operator());
    }

    @Test
    void whenIsStillAnOrdinaryNameWhereItIsNotTheGuardWord() {
        SourceUnitParseResult result = parse("""
                entity Event persistent {
                  identity id: Int64 generated auto;
                  field when: String;
                }
                error Invalid;
                input In { field when: String; }
                capability Rename {
                  input In;
                  output Unit;
                  fails Invalid;
                  requires atomic;
                  expose command;
                  workflow {
                    load Event by input.when as ev else Invalid;
                    update ev { when: input.when; }
                    persist ev;
                    return unit;
                  }
                }
                """);

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
    }

    @Test
    void aMalformedGuardIsAGrammarError() {
        for (String bad : List.of(
                "update old when old.used == false { used: true; }",
                "update old when old.used == false else { used: true; }",
                "update old when else Invalid { used: true; }",
                "update old when old.used == false else Invalid else Other { used: true; }",
                "update old whenever old.used == false else Invalid { used: true; }")) {
            SourceUnitParseResult result = parse(wrap(bad));
            assertFalse(result.isSuccess(), bad);
            assertTrue(
                    result.diagnostics().stream().map(Diagnostic::code).anyMatch(code -> code.value().startsWith("SIR-PARSE-")),
                    () -> bad + ": " + messages(result.diagnostics()));
        }
    }

    private AstUpdateStep update(String step) {
        SourceUnitParseResult result = parse(wrap(step));
        assertTrue(result.isSuccess(), () -> step + ": " + messages(result.diagnostics()));
        AstCapabilityDecl capability = (AstCapabilityDecl) ParseTestSupport.document(result.unit().orElseThrow()).software().declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        return (AstUpdateStep) capability.workflow().steps().stream()
                .filter(AstUpdateStep.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private static String wrap(String step) {
        return """
                entity Credential persistent {
                  identity id: Int64 generated auto;
                  field used: Boolean;
                }
                error Invalid;
                input In { field id: Int64; }
                capability Use {
                  input In;
                  output Unit;
                  fails Invalid;
                  requires atomic;
                  expose command;
                  workflow {
                    load Credential by input.id as old else Invalid;
                    %s
                    persist old;
                    return unit;
                  }
                }
                """.formatted(step);
    }

    private SourceUnitParseResult parse(String declarations) {
        return parser.parseProject(new SirSource(SourceId.of("tests/guard.sir"), """
                sir 0.2
                software GuardGrammar {
                  metadata {
                    displayName "Guard Grammar";
                    namespace "tests.grammar.guard";
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
                """.formatted(declarations)));
    }

    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(value -> value.code().value() + ": " + value.message())
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }
}
