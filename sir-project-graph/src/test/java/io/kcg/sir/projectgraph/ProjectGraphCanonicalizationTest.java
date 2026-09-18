package io.kcg.sir.projectgraph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.projectgraph.api.GraphEdgeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalizer;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.SemanticDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.internal.GraphCanonicalForm;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Canonical form contract: node and edge ordering, digest stability and the fact that the digest
 * really depends on graph content rather than only on the shape of the graph.
 *
 * <p>It also records that serialization is order-independent: a graph built from a shuffled input
 * must produce byte-identical canonical documents.
 */
class ProjectGraphCanonicalizationTest {

    @Test
    void nodesAreOrderedByKindRankThenCanonicalKey() {
        ProjectGraph graph = build().graph();
        List<Integer> ranks = graph.nodes().stream().map(ProjectGraphCanonicalizationTest::rank).toList();

        for (int i = 1; i < ranks.size(); i++) {
            assertTrue(ranks.get(i) >= ranks.get(i - 1),
                    "node kinds must be grouped in canonical rank order: " + ids(graph.nodes()));
        }

        for (int i = 1; i < ranks.size(); i++) {
            if (ranks.get(i).equals(ranks.get(i - 1))) {
                String previous = graph.nodes().get(i - 1).id().canonicalKey();
                String current = graph.nodes().get(i).id().canonicalKey();
                assertTrue(previous.compareTo(current) < 0,
                        "nodes of the same kind must be sorted by canonical key: " + previous + " / " + current);
            }
        }

        assertEquals("project", graph.nodes().get(0).id().canonicalKey(), "the Project root comes first");
    }

    @Test
    void edgesAreSortedByEdgeId() {
        ProjectGraph graph = build().graph();
        List<GraphEdgeId> ids = graph.edges().stream().map(ProjectGraphEdge::id).toList();
        List<GraphEdgeId> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);

        assertEquals(sorted, ids, "edges must be stored in canonical edge id order");
    }

    @Test
    void digestAndCanonicalBytesAreIndependentOfInputOrder() {
        ProjectGraph graph = build().graph();

        List<ProjectGraphNode> shuffledNodes = shuffled(graph.nodes());
        List<ProjectGraphEdge> shuffledEdges = shuffled(graph.edges());
        assertNotEquals(graph.edges(), shuffledEdges, "the fixture shuffle must actually reorder edges");

        ProjectGraphCanonicalizer canonicalizer = new ProjectGraphCanonicalizer();
        assertEquals(graph.canonicalDigest(),
                canonicalizer.digest(GraphVersion.V0_1, shuffledNodes, shuffledEdges),
                "the digest must not depend on the order of the input lists");
        assertArrayEquals(GraphCanonicalForm.canonicalBytes(GraphVersion.V0_1, graph.nodes(), graph.edges()),
                GraphCanonicalForm.canonicalBytes(GraphVersion.V0_1, shuffledNodes, shuffledEdges),
                "canonical bytes must not depend on the order of the input lists");
    }

    @Test
    void repeatedBuildsProduceTheSameDigestAndDocument() {
        ProjectGraph first = build().graph();
        ProjectGraph second = build().graph();

        assertEquals(first.canonicalDigest(), second.canonicalDigest());
        assertEquals(first.nodes(), second.nodes());
        assertEquals(first.edges(), second.edges());
        assertArrayEquals(documentBytes(first), documentBytes(second),
                "the canonical document must be byte-identical across repeated builds");    }

    @Test
    void digestDependsOnNodeContentNotOnlyOnStructure() {
        ProjectGraph base = build().graph();
        ProjectGraphInput input = ProjectGraphFixtures.campusMarket();
        SemanticDeclarationInput original = ProjectGraphFixtures.first(input.semanticDeclarations(),
                declaration -> declaration.symbolId().equals(ProjectGraphFixtures.USER_ENTITY_SYMBOL));
        SourceSpan moved = new SourceSpan(original.span().source(), original.span().start(),
                new SourcePosition(original.span().end().codePointOffset() + 5, original.span().end().line(),
                        original.span().end().column() + 5));
        SemanticDeclarationInput shifted = new SemanticDeclarationInput(original.symbolId(), original.kind(),
                original.displayName(), original.sourceNodeId(), moved);
        ProjectGraphInput mutated = ProjectGraphFixtures.withSemantics(input,
                ProjectGraphFixtures.replace(input.semanticDeclarations(),
                        input.semanticDeclarations().indexOf(original), shifted));

        ProjectGraph changed = assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(mutated)).graph();

        assertEquals(base.nodes().size(), changed.nodes().size(),
                "the mutation must not change the census, only the span");
        assertNotEquals(base.canonicalDigest(), changed.canonicalDigest(),
                "the digest must change when a provenanced span changes");
    }

    @Test
    void serializerIsIndependentOfInputOrderToo() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        ProjectGraphInput shuffledInput = ProjectGraphFixtures.rebuild(base,
                shuffled(base.semanticDeclarations()), shuffled(base.loweredDeclarations()),
                shuffled(base.artifacts()), shuffled(base.files()), shuffled(base.edges()));

        ProjectGraph ordered = build().graph();
        ProjectGraph fromShuffled = assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(shuffledInput)).graph();

        assertEquals(ordered.canonicalDigest(), fromShuffled.canonicalDigest());
        assertArrayEquals(documentBytes(ordered), documentBytes(fromShuffled),
                "shuffling every input list must still produce the same canonical document");
    }

    private static byte[] documentBytes(ProjectGraph graph) {
        ProjectGraphSerialization.Success serialization = assertInstanceOf(ProjectGraphSerialization.Success.class,
                new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1));
        return serialization.document().bytes();
    }

    private static ProjectGraphAnalysis.Success build() {
        return assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(ProjectGraphFixtures.campusMarket()));
    }

    private static int rank(ProjectGraphNode node) {
        return switch (node) {
            case ProjectGraphNode.Project p -> 0;
            case ProjectGraphNode.SemanticDeclaration s -> 1;
            case ProjectGraphNode.LoweredDeclaration l -> 2;
            case ProjectGraphNode.Artifact a -> 3;
            case ProjectGraphNode.ProjectFile f -> 4;
            default -> throw new IllegalArgumentException("unknown node kind: " + node);
        };
    }

    private static <T> List<T> shuffled(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    private static List<String> ids(List<ProjectGraphNode> nodes) {
        return nodes.stream().map(node -> node.id().canonicalKey()).collect(Collectors.toList());
    }
}
