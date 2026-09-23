package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstAnyExpression;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstExpression;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstListTypeRef;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNameExpression;
import io.kcg.sir.ast.AstNamedTypeRef;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.ast.AstStep;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.ast.AstViewField;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grammar contracts for the relation slice: the existence predicate
 * {@code any(<Entity>, <conditions>)} and the nested projection field types.
 *
 * <p>The grammar only answers whether the surface is written completely. Whether the
 * condition really connects a {@code Ref<Root>} to the root entity, whether the predicate
 * is allowed outside a {@code find} step, and how deep a projection may nest are later
 * phases' rules; this test deliberately does not assert them, so that a grammar-only
 * change cannot silently take over a semantic rule.
 */
class RelationGrammarTest {
    private static final String RELATION_SLICE = "valid/course-enrollment.sir";

    private final SirParser parser = new DefaultSirParser();

    @Test
    void theRelationSliceSourceParsesWithoutDiagnostics() {
        ParseResult result = parse(readResource(RELATION_SLICE));

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        assertTrue(result.diagnostics().isEmpty(), () -> messages(result.diagnostics()));
    }

    @Test
    void theExistencePredicateCarriesItsEntityAndItsConditions() {
        String source = readResource(RELATION_SLICE);
        AstSoftware software = software(parse(source));
        AstExpression predicate = findStep(software, "SearchCourseEnrollments").predicate();

        AstAnyExpression existence = assertInstanceOf(
                AstAnyExpression.class, predicate, "find 的存在性条件必须保留为可检查的 AST 节点");
        assertEquals("Enrollment", existence.entity().text(), "第一个参数是被引用的实体");

        AstBinaryExpression conjunction = assertInstanceOf(
                AstBinaryExpression.class, existence.conditions(), "条件里的 and 必须留在同一棵树内");
        assertEquals(AstBinaryOperator.AND, conjunction.operator(), "同一关联记录上的多个条件保持一个 and");
        assertReferenceComparison(conjunction.left(), "course", "item");
        assertReferenceComparison(conjunction.right(), "status", "EnrollmentStatus.ACTIVE");

        assertEquals(
                "any(Enrollment, course == item and status == EnrollmentStatus.ACTIVE)",
                coveredText(source, existence.span().start().codePointOffset(), existence.span().end().codePointOffset()),
                "谓词的 span 覆盖整个 any(...) 形式");
    }

    @Test
    void theExistencePredicateComposesWithOtherBooleanOperators() {
        ParseResult result = parse(findDocument("any(Enrollment, course == item) or item.code == input.code"));

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
        AstExpression predicate = findStep(software(result), "SearchCourses").predicate();
        AstBinaryExpression disjunction = assertInstanceOf(AstBinaryExpression.class, predicate);

        assertEquals(AstBinaryOperator.OR, disjunction.operator());
        assertInstanceOf(AstAnyExpression.class, disjunction.left(), "存在性谓词出现在 or 的左侧");
    }

    @Test
    void anExistencePredicateInAValidateStepIsAGrammarLevelForm() {
        ParseResult result = parse(document("""
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                }
                entity Enrollment persistent {
                  identity id: Int64 generated auto;
                  field course: Ref<Course>;
                }
                error NotAllowed;
                capability Guarded {
                  output Ref<Course>;
                  fails NotAllowed;
                  requires readonly;
                  expose query;
                  workflow {
                    validate any(Enrollment, course == item) else NotAllowed;
                    find Course where item.code == item.code as courses;
                    return courses;
                  }
                }
                """));

        assertTrue(result.isSuccess(), () -> messages(result.diagnostics()));
    }

    @Test
    void nestedProjectionFieldsKeepTheirNamedAndListTypes() {
        AstSoftware software = software(parse(readResource(RELATION_SLICE)));

        AstViewField toOne = viewField(view(software, "EnrollmentSummary"), "student");
        AstNamedTypeRef nested = assertInstanceOf(AstNamedTypeRef.class, toOne.type(), "对一投影是命名的 view 类型");
        assertEquals("StudentSummary", nested.name().text());

        AstViewField toMany = viewField(view(software, "CourseEnrollmentItem"), "enrollments");
        AstListTypeRef list = assertInstanceOf(AstListTypeRef.class, toMany.type(), "对多投影是 List<view>");
        AstNamedTypeRef element = assertInstanceOf(AstNamedTypeRef.class, list.elementType());
        assertEquals("EnrollmentSummary", element.name().text());
    }

    @Test
    void theExistencePredicateIsRejectedWithoutAnEntity() {
        assertGrammarRejected(findDocument("any(, course == item)"));
    }

    @Test
    void theExistencePredicateIsRejectedWithoutConditions() {
        assertGrammarRejected(findDocument("any(Enrollment)"));
    }

    @Test
    void theExistencePredicateIsRejectedWithoutACommaBetweenItsArguments() {
        assertGrammarRejected(findDocument("any(Enrollment course == item)"));
    }

    @Test
    void theExistencePredicateIsRejectedWithoutItsClosingParenthesis() {
        assertGrammarRejected(findDocument("any(Enrollment, course == item"));
    }

    @Test
    void anyIsNoLongerAvailableAsAnIdentifier() {
        assertGrammarRejected(document("""
                entity Sample persistent {
                  identity id: Int64 generated auto;
                  field any: String;
                }
                """));
    }

    private static void assertReferenceComparison(AstExpression expression, String field, String target) {
        AstBinaryExpression comparison = assertInstanceOf(AstBinaryExpression.class, expression);
        assertEquals(AstBinaryOperator.EQ, comparison.operator());
        AstNameExpression left = assertInstanceOf(AstNameExpression.class, comparison.left());
        assertEquals(field, left.name().text());
        if (target.startsWith("item")) {
            AstNameExpression right = assertInstanceOf(AstNameExpression.class, comparison.right());
            assertEquals(target, right.name().text());
            return;
        }

        AstMemberExpression member = assertInstanceOf(AstMemberExpression.class, comparison.right());
        AstNameExpression owner = assertInstanceOf(AstNameExpression.class, member.receiver());
        assertEquals(target.substring(0, target.indexOf('.')), owner.name().text());
        assertEquals(target.substring(target.indexOf('.') + 1), member.member().text());
    }

    private static String coveredText(String source, int startOffset, int endOffset) {
        int[] codePoints = source.codePoints().toArray();
        return new String(codePoints, startOffset, endOffset - startOffset);
    }

    private static AstSoftware software(ParseResult result) {
        return result.document().orElseThrow().software();
    }

    private static AstCapabilityDecl capability(AstSoftware software, String name) {
        return (AstCapabilityDecl) software.declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .filter(value -> ((AstCapabilityDecl) value).name().text().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static AstFindStep findStep(AstSoftware software, String capabilityName) {
        for (AstStep step : capability(software, capabilityName).workflow().steps()) {
            if (step instanceof AstFindStep find) {
                return find;
            }
        }

        throw new IllegalStateException("no find step in " + capabilityName);
    }

    private static AstViewDecl view(AstSoftware software, String name) {
        return (AstViewDecl) software.declarations().stream()
                .filter(AstViewDecl.class::isInstance)
                .filter(value -> ((AstViewDecl) value).name().text().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static AstViewField viewField(AstViewDecl view, String name) {
        return view.fields().stream()
                .filter(value -> value.name().text().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private ParseResult parse(String content) {
        return parser.parse(new SirSource(SourceId.of("tests/valid/course-enrollment.sir"), content));
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
        try (InputStream stream = RelationGrammarTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + path);
            }

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String findDocument(String predicate) {
        return document("""
                enum EnrollmentStatus {
                  ACTIVE,
                  CANCELLED
                }
                entity Course persistent {
                  identity id: Int64 generated auto;
                  field code: String;
                }
                entity Enrollment persistent {
                  identity id: Int64 generated auto;
                  field course: Ref<Course>;
                  field status: EnrollmentStatus;
                }
                input SearchInput {
                  field code: String;
                }
                capability SearchCourses {
                  input SearchInput;
                  output List<Ref<Course>>;
                  requires readonly;
                  expose query;
                  workflow {
                    find Course where %s as courses;
                    return courses;
                  }
                }
                """.formatted(predicate));
    }

    private static String document(String declarations) {
        return """
                sir 0.1
                software RelationGrammar {
                  metadata {
                    displayName "Relation Grammar";
                    namespace "tests.grammar.relation";
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
