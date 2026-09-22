package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstErrorDecl;
import io.kcg.sir.ast.AstField;
import io.kcg.sir.ast.AstInputDecl;
import io.kcg.sir.ast.AstPersistStep;
import io.kcg.sir.ast.AstPresentExpression;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstValidateStep;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for the write-side slice: the version marker, the error status,
 * the persist failure path, the patch payload, and the presence suffix.
 *
 * <p>Every negative case asserts that the document is rejected by the *grammar*
 * with at least one {@code SIR-PARSE-*} diagnostic; whether a syntactically valid
 * combination is a semantic error is a later phase's rule.
 */
class WriteGrammarTest {
    private static final String CATALOG = "valid/course-admin.sir";

    private final SirParser parser = new DefaultSirParser();

    @Test
    void theWriteSliceSourceParsesWithoutDiagnostics() {
        ParseResult result = parse(readResource(CATALOG));

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        assertTrue(result.diagnostics().isEmpty(), () -> messages(result.diagnostics()));
    }

    @Test
    void theVersionMarkerIsCarriedOnTheEntityFieldOnly() {
        AstSoftware software = parse(readResource(CATALOG)).document().orElseThrow().software();
        AstField version = entityField(software, "Course", "version");

        assertTrue(version.versioned(), "the version field carries the marker");
        assertFalse(entityField(software, "Course", "capacity").versioned());
        assertEquals(List.of(), version.constraints());
    }

    @Test
    void aViewFieldParsesTheMarkerSoALaterPhaseCanRejectIt() {
        ParseResult result = parse(document("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                }
                view Summary from Course {
                  field code: String versioned;
                }
                """));

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        AstViewDecl view = (AstViewDecl) result.document().orElseThrow().software().declarations().stream()
                .filter(AstViewDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertTrue(view.fields().get(0).versioned());
    }

    @Test
    void thePatchMarkerNamesItsSourceEntity() {
        AstSoftware software = parse(readResource(CATALOG)).document().orElseThrow().software();
        AstInputDecl patch = (AstInputDecl) software.declarations().stream()
                .filter(AstInputDecl.class::isInstance)
                .map(AstInputDecl.class::cast)
                .filter(value -> value.name().text().equals("UpdateCourseInput"))
                .findFirst()
                .orElseThrow();
        AstInputDecl plain = (AstInputDecl) software.declarations().stream()
                .filter(AstInputDecl.class::isInstance)
                .map(AstInputDecl.class::cast)
                .filter(value -> value.name().text().equals("CreateCourseInput"))
                .findFirst()
                .orElseThrow();

        assertEquals("Course", patch.patchSourceEntity().orElseThrow().text());
        assertTrue(plain.patchSourceEntity().isEmpty(), "a plain input names no entity");
    }

    @Test
    void aPatchMarkerWithoutItsEntityIsRejected() {
        assertGrammarRejected(document("""
                input UpdateInput patch {
                  field name: String;
                }
                """));

        assertGrammarRejected(document("""
                input UpdateInput patch of {
                  field name: String;
                }
                """));
    }

    @Test
    void anErrorStatusIsParsedWhenPresent() {
        AstSoftware software = parse(readResource(CATALOG)).document().orElseThrow().software();

        assertEquals(java.math.BigInteger.valueOf(404), errorStatus(software, "CourseNotFound"));
        assertEquals(java.math.BigInteger.valueOf(409), errorStatus(software, "StaleVersion"));
        assertEquals(null, errorStatus(software, "EmptyChange"));
    }

    @Test
    void aNonIntegerErrorStatusIsRejected() {
        assertGrammarRejected("error NotFound http abc;");
        assertGrammarRejected("error NotFound http 4.04;");
        assertGrammarRejected("error NotFound http;");
    }

    @Test
    void anErrorCannotDeclareTwoStatuses() {
        assertGrammarRejected("error NotFound http 404 http 409;");
    }

    @Test
    void aPersistStepCarriesItsDeclaredFailure() {
        AstSoftware software = parse(readResource(CATALOG)).document().orElseThrow().software();
        AstPersistStep failing = persistStep(software, "UpdateCourse");
        AstPersistStep plain = persistStep(software, "CreateCourse");

        assertEquals("course", failing.target().text());
        assertEquals("StaleVersion", failing.failure().orElseThrow().text());
        assertTrue(plain.failure().isEmpty(), "a persist without an else declares no failure");
    }

    @Test
    void aPersistStepCannotDeclareTwoFailures() {
        assertGrammarRejected(document("""
                capability Bad {
                  output Unit;
                  expose command;
                  workflow {
                    persist goods else First else Second;
                  }
                }
                """));
    }

    @Test
    void presenceIsAPostfixOnAReference() {
        AstSoftware software = parse(readResource(CATALOG)).document().orElseThrow().software();
        AstValidateStep step = (AstValidateStep) capability(software, "UpdateCourse").workflow().steps().get(0);

        assertTrue(
                containsPresent(step.condition()),
                "the empty-changes guard reads presence: " + step.condition());
    }

    @Test
    void aBareIdentifierCannotEndWithPresence() {
        assertGrammarRejected(document("""
                capability Bad {
                  output Unit;
                  expose command;
                  workflow {
                    persist present;
                  }
                }
                """));

        assertGrammarRejected(document("""
                capability Bad {
                  output Unit;
                  expose command;
                  workflow {
                    validate input . else NotFound;
                  }
                }
                """));
    }

    @Test
    void aVersionMarkerRequiresAFieldType() {
        assertGrammarRejected(entityDocument("field version: versioned;"));

        assertGrammarRejected(entityDocument("field version: Int64 versioned versioned;"));
    }

    @Test
    void presenceCannotBeNestedOrAppliedToALiteral() {
        assertGrammarRejected(document("""
                capability Bad {
                  output Unit;
                  expose command;
                  workflow {
                    validate input.name.present.present else NotFound;
                  }
                }
                """));

        assertGrammarRejected(document("""
                capability Bad {
                  output Unit;
                  expose command;
                  workflow {
                    validate .present else NotFound;
                  }
                }
                """));
    }

    private static boolean containsPresent(io.kcg.sir.ast.AstExpression expression) {
        return switch (expression) {
            case AstPresentExpression ignored -> true;
            case io.kcg.sir.ast.AstBinaryExpression binary ->
                    containsPresent(binary.left()) || containsPresent(binary.right());
            case io.kcg.sir.ast.AstUnaryExpression unary -> containsPresent(unary.operand());
            case io.kcg.sir.ast.AstGroupedExpression grouped -> containsPresent(grouped.inner());
            default -> false;
        };
    }

    private static AstField entityField(AstSoftware software, String entityName, String fieldName) {
        io.kcg.sir.ast.AstEntityDecl entity = (io.kcg.sir.ast.AstEntityDecl) software.declarations().stream()
                .filter(io.kcg.sir.ast.AstEntityDecl.class::isInstance)
                .filter(value -> ((io.kcg.sir.ast.AstEntityDecl) value).name().text().equals(entityName))
                .findFirst()
                .orElseThrow();
        return entity.fields().stream()
                .filter(value -> value.name().text().equals(fieldName))
                .findFirst()
                .orElseThrow();
    }

    private static java.math.BigInteger errorStatus(AstSoftware software, String name) {
        AstErrorDecl error = (AstErrorDecl) software.declarations().stream()
                .filter(AstErrorDecl.class::isInstance)
                .filter(value -> ((AstErrorDecl) value).name().text().equals(name))
                .findFirst()
                .orElseThrow();
        return error.httpStatus().orElse(null);
    }

    private static AstCapabilityDecl capability(AstSoftware software, String name) {
        return (AstCapabilityDecl) software.declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .filter(value -> ((AstCapabilityDecl) value).name().text().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static AstPersistStep persistStep(AstSoftware software, String capabilityName) {
        for (AstStep step : capability(software, capabilityName).workflow().steps()) {
            if (step instanceof AstPersistStep persist) {
                return persist;
            }
        }

        throw new IllegalStateException("no persist step in " + capabilityName);
    }

    private ParseResult parse(String content) {
        return parser.parse(new SirSource(SourceId.of("tests/valid/course-admin.sir"), content));
    }

    private void assertGrammarRejected(String content) {
        ParseResult result = parse(content);
        String diagnostics = messages(result.diagnostics());

        assertFalse(result.isSuccess(), () -> diagnostics);
        assertTrue(result.document().isEmpty(), () -> diagnostics);
        assertTrue(
                result.diagnostics().stream()
                        .map(Diagnostic::code)
                        .anyMatch(code -> code.value().startsWith("SIR-PARSE-")),
                () -> diagnostics);
    }

    private static String readResource(String path) {
        try (InputStream stream = WriteGrammarTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + path);
            }

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String entityDocument(String field) {
        return document("""
                entity Sample persistent {
                  identity id: Int64 generated auto;
                  %s
                }
                """.formatted(field));
    }

    private static String document(String declarations) {
        return """
                sir 0.1
                software WriteGrammar {
                  metadata {
                    displayName "Write Grammar";
                    namespace "tests.grammar.write";
                  }
                  target {
                    language java 21;
                    framework spring_boot;
                    persistence mybatis_plus;
                    database mysql;
                    build maven;
                    interface rest;
                  }
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
