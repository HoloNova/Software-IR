package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstEntityDecl;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstLoadStep;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for the {@code unique} field modifier and for loading by a unique field.
 *
 * <p>The negative cases assert rejection by the grammar itself; whether a well-formed combination is
 * legal (which types may be unique, whether the loaded field is unique) is a later phase's rule.
 */
class UniqueFieldGrammarTest {
    private static final String ACCOUNT = """
            entity Account persistent {
              identity id: Id generated auto;
              field name: Name where length(1, 64) unique;
              field email: Text unique @id("email");
              field nickname: Text;
              field unique: Text;
            }
            error NotFound http 404;
            input ByName { field name: Text; }
            """;

    private final SirParser parser = new DefaultSirParser();

    @Test
    void uniqueIsCarriedOnTheEntityFieldThatDeclaresIt() {
        AstSoftware software = parseOk(ACCOUNT);

        assertTrue(entityField(software, "name").unique(), "unique after the constraints");
        assertTrue(entityField(software, "email").unique(), "unique before the declared id");
        assertFalse(entityField(software, "nickname").unique());
        assertEquals("email", entityField(software, "email").declaredId().orElseThrow());
    }

    @Test
    void uniqueIsStillAnOrdinaryIdentifierWhereItIsNotTheModifier() {
        AstField named = entityField(parseOk(ACCOUNT), "unique");

        assertFalse(named.unique(), "a field called unique is not a unique field");
    }

    @Test
    void uniqueIsNotAdmittedOnInputOrViewFields() {
        assertRejected(ACCOUNT + "input Bad { field name: Text unique; }");
        assertRejected(ACCOUNT + "view Bad from Account { field name: Text unique; }");
    }

    @Test
    void aWordOtherThanUniqueAfterTheTypeIsStillAGrammarError() {
        assertRejected("entity E persistent { identity id: Id generated auto; field a: Text uniq; }");
    }

    @Test
    void loadCanNameTheUniqueFieldItLooksTheRowUpBy() {
        AstLoadStep keyed = load(parseOk(ACCOUNT + capability("load Account by name: input.name as found else NotFound;")));

        assertEquals("name", keyed.keyField().orElseThrow().text());
        assertEquals("found", keyed.result().text());
    }

    @Test
    void loadWithoutAFieldNameKeepsLoadingByIdentity() {
        AstLoadStep byIdentity = load(parseOk(ACCOUNT + capability("load Account by input.name as found else NotFound;")));

        assertTrue(byIdentity.keyField().isEmpty());
    }

    @Test
    void loadWithADanglingKeyColonIsAGrammarError() {
        assertRejected(ACCOUNT + capability("load Account by name: as found else NotFound;"));
        assertRejected(ACCOUNT + capability("load Account by : input.name as found else NotFound;"));
        assertRejected(ACCOUNT + capability("load Account by name: input.name: x as found else NotFound;"));
    }

    private static String capability(String loadStep) {
        return """
                capability Find {
                  input ByName;
                  output Account;
                  fails NotFound;
                  requires readonly;
                  expose query;
                  workflow {
                    %s
                    return found;
                  }
                }
                """.formatted(loadStep);
    }

    private static AstLoadStep load(AstSoftware software) {
        AstCapabilityDecl capability = (AstCapabilityDecl) software.declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        return (AstLoadStep) capability.workflow().steps().get(0);
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
        return parser.parseProject(new SirSource(SourceId.of("tests/unique.sir"), document(declarations)));
    }

    private static String document(String declarations) {
        return """
                sir 0.3
                software UniqueGrammar {
                  metadata {
                    displayName "Unique Grammar";
                    namespace "tests.grammar.unique";
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
