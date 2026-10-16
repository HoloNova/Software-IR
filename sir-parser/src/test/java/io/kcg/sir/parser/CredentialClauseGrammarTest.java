package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for the entity-closing {@code credential subject f expires g;} clause. Its words are
 * contextual, so a field or an entity spelled that way stays legal; whether the named fields are fit for
 * the role is a later phase's rule.
 */
class CredentialClauseGrammarTest {
    private static final String USER = "entity User persistent { identity id: Int64 generated auto; field name: String; }\n";

    private final SirParser parser = new DefaultSirParser();

    @Test
    void theClauseNamesTheSubjectAndTheExpiryFieldOfTheEntityItCloses() {
        AstEntityDecl token = entity(parseOk(USER + """
                entity AccessToken persistent {
                  identity id: Uuid generated uuid;
                  field owner: Ref<User>;
                  field expiresAt: DateTime;
                  credential subject owner expires expiresAt;
                }
                """), "AccessToken");

        assertEquals("owner", token.credential().orElseThrow().subject().text());
        assertEquals("expiresAt", token.credential().orElseThrow().expires().text());
    }

    @Test
    void anEntityWithoutTheClauseHasNone() {
        assertTrue(entity(parseOk(USER), "User").credential().isEmpty());
    }

    @Test
    void theWordsAreStillOrdinaryNamesWhereTheyAreNotTheClause() {
        AstEntityDecl odd = entity(parseOk("""
                entity credential persistent {
                  identity id: Int64 generated auto;
                  field credential: String;
                  field subject: String;
                  field expires: String;
                }
                """), "credential");

        assertTrue(odd.credential().isEmpty());
        assertEquals(3, odd.fields().size());
    }

    @Test
    void aMalformedClauseIsAGrammarError() {
        String head = USER + "entity T persistent { identity id: Uuid generated uuid; field owner: Ref<User>; field at: DateTime;\n";
        assertRejected(head + "credential owner at; }");
        assertRejected(head + "credential subject owner; }");
        assertRejected(head + "credential subject owner expires; }");
        assertRejected(head + "credential expires at subject owner; }");
        assertRejected(head + "credential subject owner expires at }");
        assertRejected(head + "credential subject owner expires at; field late: String; }");
    }

    private static AstEntityDecl entity(AstSoftware software, String name) {
        return software.declarations().stream()
                .filter(AstEntityDecl.class::isInstance)
                .map(AstEntityDecl.class::cast)
                .filter(value -> value.name().text().equals(name))
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
        return parser.parseProject(new SirSource(SourceId.of("tests/credential.sir"), document(declarations)));
    }

    private static String document(String declarations) {
        return """
                sir 0.2
                software CredentialGrammar {
                  metadata {
                    displayName "Credential Grammar";
                    namespace "tests.grammar.credential";
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
