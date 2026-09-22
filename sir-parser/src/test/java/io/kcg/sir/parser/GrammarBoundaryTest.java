package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrammarBoundaryTest {
    private final SirParser parser = new DefaultSirParser();

    @Test
    void versionMustBeOneContiguousDottedToken() {
        assertGrammarRejected(emptyDocument().replace("sir 0.1", "sir 0./*x*/01"));
        assertGrammarRejected(emptyDocument().replace("sir 0.1", "sir 0 . 1"));
    }

    @Test
    void lexicallyValidButUnsupportedVersionUsesVersionDiagnostic() {
        assertRejectedWith(
                emptyDocument().replace("sir 0.1", "sir 0.01"),
                "SIR-VERSION-001");
    }

    @Test
    void zeroArgumentConstraintCallsAreNotAlternativeSpellings() {
        assertGrammarRejected(entityDocument("field name: String where notBlank();"));
        assertGrammarRejected(entityDocument("field name: String where min();"));
    }

    @Test
    void constraintArgumentsMustBeConstants() {
        assertGrammarRejected(entityDocument("field price: Decimal where min(input.price);"));
        assertGrammarRejected(entityDocument("field price: Decimal where min(now());"));
        assertGrammarRejected(entityDocument("field price: Decimal where min(State.ACTIVE);"));
    }

    @Test
    void fieldCannotRepeatWhereClause() {
        assertGrammarRejected(entityDocument(
                "field name: String where notBlank where length(1, 100);"));
    }

    @Test
    void capabilityClausesHaveOneCanonicalOrder() {
        assertGrammarRejected(document("""
                input Request {
                  field value: String;
                }
                capability WrongOrder {
                  output Unit;
                  input Request;
                  expose command;
                  workflow { return unit; }
                }
                """));

        assertGrammarRejected(document("""
                error Problem;
                capability WrongOrder {
                  output Unit;
                  requires atomic;
                  fails Problem;
                  expose command;
                  workflow { return unit; }
                }
                """));
    }

    @Test
    void requiredAndNullableAreNotAlternativeNullabilitySyntax() {
        assertGrammarRejected(entityDocument("field name: String required;"));
        assertGrammarRejected(entityDocument("field name: String nullable;"));
    }

    @Test
    void persistenceActionHasNoSynonymKeywords() {
        for (String keyword : List.of("save", "store", "insert")) {
            assertGrammarRejected(capabilityDocument(keyword + " value;\nreturn unit;"));
        }
    }

    @Test
    void createBlockMustNotEndWithSemicolon() {
        assertGrammarRejected(document("""
                entity Product persistent {
                  identity id: Int64 generated auto;
                  field name: String;
                }
                capability CreateProduct {
                  output Ref<Product>;
                  expose command;
                  workflow {
                    create Product as product {
                      name: "sample";
                    };
                    return product;
                  }
                }
                """));
    }

    @Test
    void unknownExtensionEnvelopeIsRejected() {
        assertGrammarRejected(document("""
                extension redis {
                }
                """));
    }

    @Test
    void equalityComparisonCannotBeChained() {
        assertGrammarRejected(capabilityDocument("return a == b == c;"));
    }

    @Test
    void enumTrailingCommaIsNotAnAlternativeSpelling() {
        assertGrammarRejected(document("enum State { ACTIVE, }"));
    }

    @Test
    void viewDeclarationMustNameItsSourceEntity() {
        assertGrammarRejected(document("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                }
                view CourseSummary {
                  field code: String;
                }
                """));
        assertGrammarRejected(document("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                }
                view CourseSummary from {
                  field code: String;
                }
                """));
    }

    @Test
    void findClausesHaveOneCanonicalOrder() {
        assertGrammarRejected(queryDocument("""
                find Course
                  where item.name containsLiteral input.keyword
                  Page input.page, input.size else InvalidPageParam
                  order by code ascending
                  as courses;
                """));
        assertGrammarRejected(queryDocument("""
                find Course
                  where item.name containsLiteral input.keyword
                  as courses
                  order by code ascending;
                """));
    }

    @Test
    void orderClauseRequiresByAndAPageClauseRequiresElse() {
        assertGrammarRejected(queryDocument(
                "find Course where item.name containsLiteral input.keyword order code ascending as courses;"));
        assertGrammarRejected(queryDocument(
                "find Course where item.name containsLiteral input.keyword Page input.page, input.size as courses;"));
    }

    @Test
    void stringMatchComparisonCannotBeChained() {
        assertGrammarRejected(queryDocument(
                "find Course where item.name containsLiteral input.keyword containsLiteral input.keyword as courses;"));
    }

    @Test
    void pageContainerNeedsAnElementType() {
        assertGrammarRejected(document("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                }
                capability BadPage {
                  output Page;
                  expose query;
                  workflow {
                    find Course where item.code == "x" as courses;
                    return courses;
                  }
                }
                """));
    }

    private static String queryDocument(String workflow) {
        return document("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                  field name: String;
                }
                view CourseSummary from Course {
                  field code: String;
                }
                input SearchCoursesInput {
                  field keyword: String;
                  field page: Int32;
                  field size: Int32;
                }
                error InvalidPageParam;
                capability SearchCourses {
                  input SearchCoursesInput;
                  output Page<CourseSummary>;
                  fails InvalidPageParam;
                  requires readonly;
                  expose query;
                  workflow {
                    %s
                  }
                }
                """.formatted(workflow));
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

    private void assertRejectedWith(String content, String expectedCode) {
        ParseResult result = parse(content);
        String diagnostics = messages(result.diagnostics());

        assertFalse(result.isSuccess(), () -> diagnostics);
        assertTrue(result.document().isEmpty(), () -> diagnostics);
        assertTrue(
                result.diagnostics().stream()
                        .map(Diagnostic::code)
                        .anyMatch(code -> code.value().equals(expectedCode)),
                () -> diagnostics);
    }

    private ParseResult parse(String content) {
        return parser.parse(new SirSource(SourceId.of("tests/invalid/grammar-boundary.sir"), content));
    }

    private static String emptyDocument() {
        return document("");
    }

    private static String entityDocument(String field) {
        return document("""
                entity Sample persistent {
                  identity id: Int64 generated auto;
                  %s
                }
                """.formatted(field));
    }

    private static String capabilityDocument(String workflow) {
        return document("""
                capability BoundaryCapability {
                  output Unit;
                  expose command;
                  workflow {
                    %s
                  }
                }
                """.formatted(workflow));
    }

    private static String document(String declarations) {
        return """
                sir 0.1
                software GrammarBoundary {
                  metadata {
                    displayName "Grammar Boundary";
                    namespace "tests.grammar.boundary";
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
