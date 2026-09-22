package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstBinaryExpression;
import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.ast.AstDeclaration;
import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstFindOrder;
import io.kcg.sir.ast.AstFindOrderKey;
import io.kcg.sir.ast.AstFindPage;
import io.kcg.sir.ast.AstFindStep;
import io.kcg.sir.ast.AstMemberExpression;
import io.kcg.sir.ast.AstNamedTypeRef;
import io.kcg.sir.ast.AstNode;
import io.kcg.sir.ast.AstPageTypeRef;
import io.kcg.sir.ast.AstViewDecl;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parser-level contract for the Q9 query slice: the {@code view} declaration,
 * the {@code Page<T>} output container, and the {@code find} step's ordering
 * and pagination clauses.
 *
 * <p>The parser only proves shape and stable identity. Every rule about which
 * entity a projection may bind to, which fields may be ordered, and what the
 * pagination clauses mean belongs to later phases.
 */
class QueryGrammarTest {
    private static final String CATALOG = "valid/course-catalog.sir";
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void viewDeclarationCarriesItsSourceEntityAndOrderedFields() {
        AstViewDecl view = declaration(1, AstViewDecl.class);

        assertEquals("CourseSummary", view.name().text());
        assertEquals("Course", view.sourceEntity().text());
        assertEquals(
                List.of("code", "name", "capacity"),
                view.fields().stream().map(field -> field.name().text()).toList());
        assertTrue(view.fields().stream().allMatch(field -> field.constraints().isEmpty()));
    }

    @Test
    void viewFieldsStillAcceptConstraintsSoThatValidateOwnsThatRule() {
        ParseResult result = parseText("valid/course-catalog-with-constraint.sir", resource(CATALOG)
                .replace("field code: String;", "field code: String where notBlank;"));

        assertTrue(result.isSuccess(), result.diagnostics().toString());
        AstViewDecl view = (AstViewDecl) result.document().orElseThrow().software().declarations().get(1);
        assertEquals(1, view.fields().getFirst().constraints().size());
    }

    @Test
    void queryOutputIsAPageOfAViewWithoutExtraSyntax() {
        AstCapabilityDecl capability = declaration(4, AstCapabilityDecl.class);

        AstPageTypeRef page = assertInstanceOf(AstPageTypeRef.class, capability.output().type());
        AstNamedTypeRef element = assertInstanceOf(AstNamedTypeRef.class, page.elementType());
        assertEquals("CourseSummary", element.name().text());
        assertEquals(AstExposureKind.QUERY, capability.exposure().exposure());
    }

    @Test
    void findStepCarriesOrderKeysAndPageSourcesWithoutLosingItsPredicate() {
        AstFindStep find = findStep(parseDocument(CATALOG));

        assertEquals("Course", find.entity().text());
        AstBinaryExpression predicate = assertInstanceOf(AstBinaryExpression.class, find.predicate());
        assertEquals(AstBinaryOperator.CONTAINS_LITERAL, predicate.operator());

        AstFindOrder order = find.order().orElseThrow();
        assertEquals(
                List.of("code", "id"),
                order.keys().stream().map(key -> key.field().text()).toList());
        assertTrue(order.keys().stream().noneMatch(AstFindOrderKey::descending));

        AstFindPage page = find.page().orElseThrow();
        assertEquals("page", ((AstMemberExpression) page.page()).member().text());
        assertEquals("size", ((AstMemberExpression) page.size()).member().text());
        assertEquals("InvalidPageParam", page.error().text());
        assertEquals("courses", find.result().text());
    }

    @Test
    void descendingOrderDirectionIsExplicitAndOptional() {
        ParseResult result = parseText("valid/course-catalog-descending.sir", resource(CATALOG)
                .replace("order by code ascending, id ascending", "order by code descending, id ascending"));

        assertTrue(result.isSuccess(), result.diagnostics().toString());
        AstFindOrder order = findStep(result).order().orElseThrow();
        assertTrue(order.keys().getFirst().descending());
        assertFalse(order.keys().get(1).descending());
    }

    @Test
    void queryGrammarProducesStableNodeIdsAcrossRuns() {
        assertEquals(queryNodeIds(), queryNodeIds());
    }

    @Test
    void orderAndPageClausesAreOptionalSoExistingFindStepsKeepTheirShape() {
        AstFindStep plain = findStep(parseDocument("valid/all-workflow-steps.sir"));

        assertTrue(plain.order().isEmpty());
        assertTrue(plain.page().isEmpty());
    }

    private AstFindStep findStep(ParseResult result) {
        AstDeclaration declaration = result.document()
                .orElseThrow()
                .software()
                .declarations()
                .stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        AstCapabilityDecl capability = assertInstanceOf(AstCapabilityDecl.class, declaration);
        return capability.workflow().steps().stream()
                .filter(AstFindStep.class::isInstance)
                .map(AstFindStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private <T extends AstDeclaration> T declaration(int index, Class<T> type) {
        return assertInstanceOf(type, parseDocument(CATALOG)
                .document()
                .orElseThrow()
                .software()
                .declarations()
                .get(index));
    }

    private List<String> queryNodeIds() {
        AstCapabilityDecl capability = declaration(4, AstCapabilityDecl.class);
        return capability.workflow().steps().stream()
                .filter(AstFindStep.class::isInstance)
                .map(AstFindStep.class::cast)
                .map(step -> step.id().value() + "|" + step.order()
                        .orElseThrow()
                        .keys()
                        .stream()
                        .map(key -> key.id().value())
                        .toList())
                .toList();
    }

    private ParseResult parseDocument(String resourcePath) {
        return parseText(resourcePath, resource(resourcePath));
    }

    private ParseResult parseText(String label, String content) {
        return parser.parse(new SirSource(SourceId.of("tests/" + label), content));
    }

    private static String resource(String path) {
        try (var stream = QueryGrammarTest.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
