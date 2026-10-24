package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the graph adapter in memory, without the platform-specific safe directory/export stage. */
class RelatedCountProjectGraphContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"topics/library/project.sir", "valid/g9-topic-conformance.sir"})
    void relatedCountSupportTracesFromProjectThroughItsOwnRoleToTheExactFile(String resource) throws Exception {
        var source = source(resource);
        var compiled = compile(source);
        var supports = compiled.loweredModel().projectArtifacts().stream()
                .filter(ProjectArtifact.RelatedCounts.class::isInstance).toList();
        assertEquals(1, supports.size(), "multiple count readers share one project support artifact");
        var support = supports.getFirst();
        var generated = compiled.generatedFiles().stream().filter(f -> f.artifactId().equals(support.id())).toList();
        assertEquals(1, generated.size());
        var file = generated.getFirst();
        assertEquals(support.path(), file.relativePath());
        assertTrue(file.relativePath().endsWith("/RelatedCounts.java"));
        assertTrue(file.symbolId().isEmpty(), "project support must not belong to an arbitrary entity");

        var input = new SpringBootProjectGraphInputFactory().build(
                compiled.semanticModel(), compiled.loweredModel(), compiled.generatedFiles(), source);
        var artifacts = input.artifacts().stream().filter(a -> a.artifactId().equals(support.id())).toList();
        assertEquals(1, artifacts.size());
        var artifact = artifacts.getFirst();
        assertEquals("RELATED_COUNTS", assertInstanceOf(ArtifactRole.ProjectRole.class, artifact.role()).name());
        assertEquals(support.path(), artifact.qualifiedName());
        assertEquals(support.origin(), artifact.origin());
        assertTrue(artifact.ownerSymbol().isEmpty());
        assertEquals(1, input.artifacts().stream().filter(a -> a.role().equals(artifact.role())).count());
        assertEquals(0, input.artifacts().stream().filter(a -> a.role() == ArtifactRole.ProjectRole.EXACT_AGGREGATE).count(),
                "integer count support must not masquerade as monetary SUM support");

        var graph = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphBuilder().build(input)).graph();
        var supportId = new GraphNodeId.Lowered(support.id());
        var node = assertInstanceOf(ProjectGraphNode.Artifact.class, graph.node(supportId).orElseThrow());
        assertEquals(artifact.role(), node.role());
        assertEquals(support.origin(), node.provenance().origin());
        assertEquals(source.entry(), node.provenance().sourceId());
        var owners = graph.incoming(supportId).stream().filter(e -> e.kind() == GraphEdgeKind.OWNS_ARTIFACT).toList();
        assertEquals(1, owners.size());
        assertEquals(GraphNodeId.ProjectNodeId.INSTANCE, owners.getFirst().source());
        var outputs = graph.outgoing(supportId).stream().filter(e -> e.kind() == GraphEdgeKind.GENERATES_FILE).toList();
        assertEquals(1, outputs.size());
        var fileId = new GraphNodeId.File(file.relativePath());
        assertEquals(fileId, outputs.getFirst().target());
        var fileNode = assertInstanceOf(ProjectGraphNode.ProjectFile.class, graph.node(fileId).orElseThrow());
        assertEquals(support.id(), fileNode.provenance().artifactId());
        byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
        assertEquals(bytes.length, fileNode.provenance().byteCount());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), fileNode.provenance().sha256Hex());
        assertTrue(fileNode.provenance().ownerSymbol().isEmpty());
        assertRoundTrip(graph);
    }

    @ParameterizedTest
    @ValueSource(strings = {"topics/market/project.sir", "topics/ledger/project.sir"})
    void programsWithoutViewCountsDoNotAcquireCountArtifactsOrRoles(String resource) throws Exception {
        var source = source(resource);
        var compiled = compile(source);
        assertFalse(compiled.loweredModel().projectArtifacts().stream().anyMatch(ProjectArtifact.RelatedCounts.class::isInstance));
        assertFalse(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/RelatedCounts.java")));
        var input = new SpringBootProjectGraphInputFactory().build(
                compiled.semanticModel(), compiled.loweredModel(), compiled.generatedFiles(), source);
        assertFalse(input.artifacts().stream().anyMatch(a -> a.role() instanceof ArtifactRole.ProjectRole role
                && role.name().equals("RELATED_COUNTS")));
        assertEquals(resource.contains("ledger") ? 1 : 0,
                input.artifacts().stream().filter(a -> a.role() == ArtifactRole.ProjectRole.EXACT_AGGREGATE).count());
        var graph = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphBuilder().build(input)).graph();
        assertRoundTrip(graph);
    }

    private static void assertRoundTrip(ProjectGraph graph) {
        var encoded = assertInstanceOf(ProjectGraphSerialization.Success.class, new ProjectGraphSerializer().serialize(graph));
        var restored = assertInstanceOf(ProjectGraphAnalysis.Success.class, new ProjectGraphLoader().load(encoded.document())).graph();
        assertEquals(graph.canonicalDigest(), restored.canonicalDigest());
        assertEquals(graph.nodes(), restored.nodes());
        assertEquals(graph.edges(), restored.edges());
        var reencoded = assertInstanceOf(ProjectGraphSerialization.Success.class, new ProjectGraphSerializer().serialize(restored));
        assertArrayEquals(encoded.document().bytes(), reencoded.document().bytes());
    }

    private static SourceSnapshot source(String resource) throws Exception {
        return ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource(resource));
    }

    private static ProjectCompilation.Compiled compile(SourceSnapshot source) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var result = ProjectCompilation.compile(source, new SpringBootGenerator()::generate, diagnostics);
        assertTrue(result.isPresent(), diagnostics.toString());
        return result.orElseThrow();
    }
}
