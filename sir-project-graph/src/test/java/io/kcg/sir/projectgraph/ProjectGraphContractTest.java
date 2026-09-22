package io.kcg.sir.projectgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ProjectGraphValidator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Direct module contract for {@link ProjectGraphBuilder} and {@link ProjectGraph}: the four edge
 * kinds of the Project Symbol Graph, the resulting node/edge census, identity-based traceability
 * and the immutability of every public collection.
 *
 * <p>This is the module-level counterpart of the Application-level
 * {@code ToolchainProjectGraphIntegrationTest}: that test proves the real compilation chain produces
 * such a graph, this one pins the graph contract itself without depending on the compiler.
 */
class ProjectGraphContractTest {

    private static final int EXPECTED_NODES = 35;
    private static final int EXPECTED_EDGES = 34;

    @Test
    void campusMarketShapedFixtureBuildsAValidGraph() {
        ProjectGraphAnalysis.Success success = assertInstanceOf(ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(ProjectGraphFixtures.campusMarket()));

        assertEquals(List.of(), success.diagnostics(), "a valid input must build without diagnostics");

        ProjectGraph graph = success.graph();
        assertEquals(GraphVersion.V0_1, graph.version());
        assertEquals(EXPECTED_NODES, graph.nodes().size(), () -> summarize(graph));
        assertEquals(EXPECTED_EDGES, graph.edges().size(), () -> summarize(graph));
        assertTrue(graph.canonicalDigest().matches("[0-9a-f]{64}"),
                "canonical digest must be a 64-character lowercase SHA-256 hex string: "
                        + graph.canonicalDigest());
    }

    @Test
    void graphContainsTheExpectedNodeAndEdgeKinds() {
        ProjectGraph graph = build().graph();

        Map<String, Integer> nodeKinds = new java.util.LinkedHashMap<>();
        for (ProjectGraphNode node : graph.nodes()) {
            nodeKinds.merge(node.getClass().getSimpleName(), 1, Integer::sum);
        }
        assertEquals(1, nodeKinds.getOrDefault("Project", 0), () -> nodeKinds.toString());
        assertEquals(6, nodeKinds.getOrDefault("SemanticDeclaration", 0), () -> nodeKinds.toString());
        assertEquals(6, nodeKinds.getOrDefault("LoweredDeclaration", 0), () -> nodeKinds.toString());
        assertEquals(11, nodeKinds.getOrDefault("Artifact", 0), () -> nodeKinds.toString());
        assertEquals(11, nodeKinds.getOrDefault("ProjectFile", 0), () -> nodeKinds.toString());

        Map<GraphEdgeKind, Integer> edgeKinds = new EnumMap<>(GraphEdgeKind.class);
        for (ProjectGraphEdge edge : graph.edges()) {
            edgeKinds.merge(edge.kind(), 1, Integer::sum);
        }
        assertEquals(6, edgeKinds.getOrDefault(GraphEdgeKind.DECLARES, 0), () -> edgeKinds.toString());
        assertEquals(6, edgeKinds.getOrDefault(GraphEdgeKind.LOWERS_TO, 0), () -> edgeKinds.toString());
        assertEquals(11, edgeKinds.getOrDefault(GraphEdgeKind.OWNS_ARTIFACT, 0), () -> edgeKinds.toString());
        assertEquals(11, edgeKinds.getOrDefault(GraphEdgeKind.GENERATES_FILE, 0), () -> edgeKinds.toString());
    }

    @Test
    void everyDeclaresEdgeStartsAtTheProjectRoot() {
        ProjectGraph graph = build().graph();
        GraphNodeId.ProjectNodeId projectId = GraphNodeId.ProjectNodeId.INSTANCE;

        for (ProjectGraphEdge edge : graph.edges()) {
            if (edge.kind() == GraphEdgeKind.DECLARES) {
                assertEquals(projectId, edge.source(), "DECLARES must start at the Project root");
                assertInstanceOf(GraphNodeId.Semantic.class, edge.target());
            }
        }

        assertEquals(8, graph.outgoing(projectId).size(),
                "the Project root must declare 6 top-level symbols and own 2 project artifacts");
        assertEquals(0, graph.incoming(projectId).size(), "nothing may point at the Project root");
    }

    @Test
    void entitySymbolTracesToItsArtifactsAndFiles() {
        ProjectGraph graph = build().graph();
        GraphNodeId.Semantic userSymbol = ProjectGraphFixtures.semanticNode(ProjectGraphFixtures.USER_ENTITY_SYMBOL);

        assertTrue(graph.node(userSymbol).isPresent(), "the User semantic node must exist");
        List<ProjectGraphEdge> lowersTo = graph.outgoing(userSymbol);
        assertEquals(1, lowersTo.size(), "a top-level symbol lowers to exactly one declaration");
        assertEquals(GraphEdgeKind.LOWERS_TO, lowersTo.get(0).kind());

        GraphNodeId.Lowered loweredId = (GraphNodeId.Lowered) lowersTo.get(0).target();
        List<ProjectGraphEdge> owned = graph.outgoing(loweredId).stream()
                .filter(edge -> edge.kind() == GraphEdgeKind.OWNS_ARTIFACT)
                .toList();
        assertEquals(2, owned.size(), "the User entity owns an entity model and a mapper");

        Set<ArtifactRole> roles = new HashSet<>();
        Set<String> generatedPaths = new HashSet<>();
        for (ProjectGraphEdge edge : owned) {
            GraphNodeId.Lowered artifactId = (GraphNodeId.Lowered) edge.target();
            ProjectGraphNode.Artifact artifact = (ProjectGraphNode.Artifact) graph.node(artifactId).orElseThrow();
            roles.add(artifact.role());
            assertEquals(ProjectGraphFixtures.USER_ENTITY_SYMBOL, artifact.ownerSymbol().orElseThrow());

            List<ProjectGraphEdge> generates = graph.outgoing(artifactId).stream()
                    .filter(e -> e.kind() == GraphEdgeKind.GENERATES_FILE)
                    .toList();
            assertEquals(1, generates.size(), "each artifact generates exactly one file");
            generatedPaths.add(((GraphNodeId.File) generates.get(0).target()).relativePath());
        }

        assertEquals(Set.of(ArtifactRole.DeclarationRole.ENTITY_MODEL, ArtifactRole.DeclarationRole.MAPPER), roles);
        assertEquals(2, generatedPaths.size());
        assertTrue(generatedPaths.stream().anyMatch(path -> path.endsWith("/User.java")), () -> generatedPaths.toString());
        assertTrue(generatedPaths.stream().anyMatch(path -> path.endsWith("/UserMapper.java")),
                () -> generatedPaths.toString());

        assertEquals(2, graph.artifactsFor(ProjectGraphFixtures.USER_ENTITY_SYMBOL).size(),
                "artifactsFor must index artifacts by owner symbol");
        assertEquals(1, graph.filesForArtifact(ProjectGraphFixtures.artifactId(
                ProjectGraphFixtures.USER_ENTITY_SYMBOL, ArtifactRole.DeclarationRole.MAPPER)).size(),
                "filesForArtifact must index project files by artifact id");
    }

    @Test
    void projectArtifactsHaveNoOwnerSymbol() {
        ProjectGraph graph = build().graph();

        List<ProjectGraphNode.Artifact> projectArtifacts = graph.nodes().stream()
                .filter(node -> node instanceof ProjectGraphNode.Artifact artifact
                        && artifact.role() instanceof ArtifactRole.ProjectRole)
                .map(node -> (ProjectGraphNode.Artifact) node)
                .toList();

        assertEquals(2, projectArtifacts.size(), "MAVEN_PROJECT and APPLICATION_MAIN");
        Set<ArtifactRole> roles = new HashSet<>();
        for (ProjectGraphNode.Artifact artifact : projectArtifacts) {
            roles.add(artifact.role());
            assertTrue(artifact.ownerSymbol().isEmpty(), "project artifacts carry no owner symbol");
            assertTrue(artifact.provenance().origin().ownerSymbol().isEmpty(),
                    "project artifact origins carry no owner symbol");
            assertTrue(graph.filesForArtifact(artifact.artifactId()).size() == 1,
                    "each project artifact generates exactly one file");
        }
        assertEquals(Set.of(ArtifactRole.ProjectRole.MAVEN_PROJECT, ArtifactRole.ProjectRole.APPLICATION_MAIN), roles);
    }

    @Test
    void everyNodeIsProvenancedToTheGraphSourceId() {
        ProjectGraph graph = build().graph();
        for (ProjectGraphNode node : graph.nodes()) {
            assertEquals(ProjectGraphFixtures.SOURCE_ID, node.provenance().sourceId(),
                    "every node must be provenanced to the graph source id: " + node.id().canonicalKey());
        }
        GraphNodeId.ProjectNodeId projectId = GraphNodeId.ProjectNodeId.INSTANCE;
        assertEquals(GraphNodeId.ProjectNodeId.INSTANCE.canonicalKey(), projectId.canonicalKey());
        assertTrue(graph.node(projectId).isPresent(), "the Project node must be addressable by its id");
    }

    @Test
    void publicCollectionsAreImmutable() {
        ProjectGraph graph = build().graph();
        GraphNodeId.ProjectNodeId projectId = GraphNodeId.ProjectNodeId.INSTANCE;
        GraphNodeId.Semantic semantic = ProjectGraphFixtures.semanticNode(ProjectGraphFixtures.USER_ENTITY_SYMBOL);
        LoweredNodeId artifactId = ProjectGraphFixtures.artifactId(ProjectGraphFixtures.USER_ENTITY_SYMBOL,
                ArtifactRole.DeclarationRole.ENTITY_MODEL);

        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().add(null));
        assertThrows(UnsupportedOperationException.class, () -> graph.edges().add(null));
        assertThrows(UnsupportedOperationException.class, () -> graph.outgoing(projectId).add(null));
        assertThrows(UnsupportedOperationException.class, () -> graph.incoming(semantic).add(null));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.artifactsFor(ProjectGraphFixtures.USER_ENTITY_SYMBOL).add(null));
        assertThrows(UnsupportedOperationException.class, () -> graph.filesForArtifact(artifactId).add(null));
    }

    @Test
    void unknownIdsYieldEmptyResultsInsteadOfFailures() {
        ProjectGraph graph = build().graph();
        GraphNodeId.Semantic unknown = ProjectGraphFixtures.semanticNode(
                ProjectGraphFixtures.symbol("entity", "DoesNotExist"));

        assertTrue(graph.node(unknown).isEmpty());
        assertEquals(List.of(), graph.outgoing(unknown));
        assertEquals(List.of(), graph.incoming(unknown));
        assertEquals(List.of(), graph.artifactsFor(ProjectGraphFixtures.symbol("entity", "DoesNotExist")));
        assertEquals(List.of(), graph.filesForArtifact(new LoweredNodeId("artifact://unknown")));
        assertFalse(graph.nodes().isEmpty());
    }

    @Test
    void validatorAcceptsTheCanonicalGraphFromTheBuilder() {
        ProjectGraph graph = build().graph();
        List<io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic> diagnostics =
                new ProjectGraphValidator().validate(graph.version(), graph.nodes(), graph.edges());
        assertEquals(List.of(), diagnostics, () -> diagnostics.toString());
    }

    private static ProjectGraphAnalysis.Success build() {
        ProjectGraphAnalysis analysis = new ProjectGraphBuilder().build(ProjectGraphFixtures.campusMarket());
        if (analysis instanceof ProjectGraphAnalysis.Failure failure) {
            throw new AssertionError("the default fixture must be a valid graph: " + failure.diagnostics());
        }
        return (ProjectGraphAnalysis.Success) analysis;
    }

    private static String summarize(ProjectGraph graph) {
        Map<String, Integer> nodeKinds = new java.util.LinkedHashMap<>();
        for (ProjectGraphNode node : graph.nodes()) {
            nodeKinds.merge(node.getClass().getSimpleName(), 1, Integer::sum);
        }
        Map<GraphEdgeKind, Integer> edgeKinds = new EnumMap<>(GraphEdgeKind.class);
        for (ProjectGraphEdge edge : graph.edges()) {
            edgeKinds.merge(edge.kind(), 1, Integer::sum);
        }
        return "nodes=" + graph.nodes().size() + " " + nodeKinds + ", edges=" + graph.edges().size() + " " + edgeKinds;
    }
}
