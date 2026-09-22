package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ExecutionManifest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.internal.SpringBootProjectGraphInputFactory;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphProvenance;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * campus-market end-to-end Project Symbol Graph integration. Verifies that
 * the full public pipeline produces a valid, immutable ProjectGraph whose
 * nodes, edges, provenance and file metadata are consistent with the
 * ExecutionManifest, and that Entity/Capability SymbolIds trace through
 * Lowered declarations and Artifacts to exact project-relative file paths.
 *
 * <p>Also verifies determinism: the canonical digest is stable across
 * repeated runs and across different absolute output roots.
 */
class ToolchainProjectGraphIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    // campus-market declaration SymbolIds (softwareName = "CampusMarket").
    private static final SymbolId ENUM_SYMBOL =
            new SymbolId("sir://CampusMarket/enum/GoodsStatus");
    private static final SymbolId USER_ENTITY_SYMBOL =
            new SymbolId("sir://CampusMarket/entity/User");
    private static final SymbolId GOODS_ENTITY_SYMBOL =
            new SymbolId("sir://CampusMarket/entity/Goods");
    private static final SymbolId INPUT_SYMBOL =
            new SymbolId("sir://CampusMarket/input/PublishGoodsInput");
    private static final SymbolId ERROR_SYMBOL =
            new SymbolId("sir://CampusMarket/error/InvalidGoodsPrice");
    private static final SymbolId CAPABILITY_SYMBOL =
            new SymbolId("sir://CampusMarket/capability/PublishGoods");

    @Test
    void campusMarketProducesNonEmptyGraphOnSuccess() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();
        assertNotNull(graph, "Success must carry a non-null ProjectGraph");
        assertFalse(graph.nodes().isEmpty(), "graph must contain nodes");
        assertFalse(graph.edges().isEmpty(), "graph must contain edges");
        assertNotNull(graph.canonicalDigest(), "graph must have a canonical digest");
        assertEquals(io.kcg.sir.projectgraph.api.GraphVersion.V0_1, graph.version());
    }

    @Test
    void graphHasExpectedNodeAndEdgeCounts() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();

        // 1 Project + 6 Semantic (enum, 2 entities, input, error, capability)
        // + 6 Lowered + 16 Artifacts (9 declaration + 7 project) + 16 Files
        // = 45 nodes.
        // The project-level count grew from 2 to 7 with the error contract the write slice needs:
        // the response envelope, the failure base class, the failure advice, the constraint
        // primitives, and the application configuration are artifacts of the target itself.
        assertEquals(45, graph.nodes().size(),
                "expected 45 graph nodes: " + summarizeNodes(graph));

        // 6 DECLARES + 6 LOWERS_TO + 16 OWNS_ARTIFACT + 16 GENERATES_FILE
        // = 44 edges.
        assertEquals(44, graph.edges().size(),
                "expected 44 graph edges: " + summarizeEdges(graph));
    }

    @Test
    void entityUserSymbolTracesToLoweredArtifactsAndFiles() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();

        // SemanticDeclaration for User entity.
        GraphNodeId.Semantic userSemantic = new GraphNodeId.Semantic(USER_ENTITY_SYMBOL);
        Optional<ProjectGraphNode> semanticNode = graph.node(userSemantic);
        assertTrue(semanticNode.isPresent(), "User SemanticDeclaration must exist");
        assertInstanceOf(ProjectGraphNode.SemanticDeclaration.class, semanticNode.get());

        // LOWERS_TO edge from Semantic to Lowered.
        List<ProjectGraphEdge> lowersEdges = graph.outgoing(userSemantic);
        assertEquals(1, lowersEdges.size(),
                "User Semantic must have exactly one outgoing LOWERS_TO edge");
        assertEquals(GraphEdgeKind.LOWERS_TO, lowersEdges.get(0).kind());
        GraphNodeId.Lowered userLoweredId = (GraphNodeId.Lowered) lowersEdges.get(0).target();

        // OWNS_ARTIFACT edges from Lowered to Artifacts.
        List<ProjectGraphEdge> ownsEdges = graph.outgoing(userLoweredId).stream()
                .filter(e -> e.kind() == GraphEdgeKind.OWNS_ARTIFACT)
                .toList();
        assertEquals(2, ownsEdges.size(),
                "User entity must own 2 artifacts (ENTITY_MODEL + MAPPER)");

        // Collect artifact roles and their generated file paths.
        Map<ArtifactRole, String> roleToPath = new LinkedHashMap<>();
        for (ProjectGraphEdge edge : ownsEdges) {
            GraphNodeId.Lowered artifactId = (GraphNodeId.Lowered) edge.target();
            Optional<ProjectGraphNode> artifactNode = graph.node(artifactId);
            assertTrue(artifactNode.isPresent(), "artifact must exist");
            ProjectGraphNode.Artifact artifact = (ProjectGraphNode.Artifact) artifactNode.get();
            roleToPath.put(artifact.role(), artifact.qualifiedName());

            // GENERATES_FILE edges from Artifact to ProjectFile.
            List<ProjectGraphEdge> genEdges = graph.outgoing(artifactId).stream()
                    .filter(e -> e.kind() == GraphEdgeKind.GENERATES_FILE)
                    .toList();
            assertEquals(1, genEdges.size(),
                    "each User artifact must generate exactly one file");
            GraphNodeId.File fileId = (GraphNodeId.File) genEdges.get(0).target();
            assertTrue(fileId.relativePath().endsWith("User.java")
                            || fileId.relativePath().endsWith("UserMapper.java"),
                    "User artifact file must be User.java or UserMapper.java: "
                            + fileId.relativePath());
        }
        assertTrue(roleToPath.containsKey(ArtifactRole.DeclarationRole.ENTITY_MODEL),
                "User must have ENTITY_MODEL artifact");
        assertTrue(roleToPath.containsKey(ArtifactRole.DeclarationRole.MAPPER),
                "User must have MAPPER artifact");
    }

    @Test
    void capabilityPublishGoodsTracesToServiceAndControllerFiles() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();

        GraphNodeId.Semantic capSemantic = new GraphNodeId.Semantic(CAPABILITY_SYMBOL);
        List<ProjectGraphEdge> lowersEdges = graph.outgoing(capSemantic);
        assertEquals(1, lowersEdges.size(), "Capability must have one LOWERS_TO edge");
        GraphNodeId.Lowered capLoweredId = (GraphNodeId.Lowered) lowersEdges.get(0).target();

        List<ProjectGraphEdge> ownsEdges = graph.outgoing(capLoweredId).stream()
                .filter(e -> e.kind() == GraphEdgeKind.OWNS_ARTIFACT)
                .toList();
        assertEquals(2, ownsEdges.size(),
                "Capability must own 2 artifacts (SERVICE + CONTROLLER)");

        Set<String> generatedPaths = new HashSet<>();
        for (ProjectGraphEdge edge : ownsEdges) {
            GraphNodeId.Lowered artifactId = (GraphNodeId.Lowered) edge.target();
            ProjectGraphNode.Artifact artifact =
                    (ProjectGraphNode.Artifact) graph.node(artifactId).orElseThrow();
            assertTrue(artifact.role() == ArtifactRole.DeclarationRole.SERVICE
                            || artifact.role() == ArtifactRole.DeclarationRole.CONTROLLER,
                    "Capability artifact role must be SERVICE or CONTROLLER: " + artifact.role());

            List<ProjectGraphEdge> genEdges = graph.outgoing(artifactId).stream()
                    .filter(e -> e.kind() == GraphEdgeKind.GENERATES_FILE)
                    .toList();
            assertEquals(1, genEdges.size());
            generatedPaths.add(((GraphNodeId.File) genEdges.get(0).target()).relativePath());
        }
        assertTrue(generatedPaths.stream().anyMatch(p -> p.endsWith("PublishGoodsService.java")),
                "must generate PublishGoodsService.java: " + generatedPaths);
        assertTrue(generatedPaths.stream().anyMatch(p -> p.endsWith("PublishGoodsController.java")),
                "must generate PublishGoodsController.java: " + generatedPaths);
    }

    /** Every project-level role the Spring Boot target emits, whatever the program declares. */
    private static final Set<ArtifactRole> PROJECT_ROLES = Set.of(
            ArtifactRole.ProjectRole.MAVEN_PROJECT,
            ArtifactRole.ProjectRole.APPLICATION_MAIN,
            ArtifactRole.ProjectRole.API_ERROR_RESPONSE,
            ArtifactRole.ProjectRole.API_EXCEPTION_BASE,
            ArtifactRole.ProjectRole.API_EXCEPTION_ADVICE,
            ArtifactRole.ProjectRole.VALIDATION_SUPPORT,
            ArtifactRole.ProjectRole.APPLICATION_CONFIG);

    @Test
    void projectRootTracesToPomXmlAndApplicationJava() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();

        GraphNodeId.ProjectNodeId projectId = GraphNodeId.ProjectNodeId.INSTANCE;
        List<ProjectGraphEdge> ownsEdges = graph.outgoing(projectId).stream()
                .filter(e -> e.kind() == GraphEdgeKind.OWNS_ARTIFACT)
                .toList();
        // Project root must own every project-level artifact the target emits, exactly once each.
        assertEquals(PROJECT_ROLES.size(), ownsEdges.size(),
                "Project root must own every project artifact: " + ownsEdges.size());

        Set<String> projectFilePaths = new HashSet<>();
        Set<ArtifactRole> projectRoles = new HashSet<>();
        for (ProjectGraphEdge edge : ownsEdges) {
            GraphNodeId.Lowered artifactId = (GraphNodeId.Lowered) edge.target();
            ProjectGraphNode.Artifact artifact =
                    (ProjectGraphNode.Artifact) graph.node(artifactId).orElseThrow();
            projectRoles.add(artifact.role());
            assertTrue(artifact.ownerSymbol().isEmpty(),
                    "project artifact must have empty ownerSymbol");

            List<ProjectGraphEdge> genEdges = graph.outgoing(artifactId).stream()
                    .filter(e -> e.kind() == GraphEdgeKind.GENERATES_FILE)
                    .toList();
            assertEquals(1, genEdges.size(),
                    "each project artifact must generate exactly one file");
            projectFilePaths.add(((GraphNodeId.File) genEdges.get(0).target()).relativePath());
        }
        assertTrue(projectRoles.contains(ArtifactRole.ProjectRole.MAVEN_PROJECT),
                "must have MAVEN_PROJECT role: " + projectRoles);
        assertTrue(projectRoles.contains(ArtifactRole.ProjectRole.APPLICATION_MAIN),
                "must have APPLICATION_MAIN role: " + projectRoles);
        assertTrue(projectFilePaths.contains("pom.xml"),
                "must generate pom.xml: " + projectFilePaths);
        assertTrue(projectFilePaths.stream().anyMatch(p -> p.endsWith("Application.java")),
                "must generate Application.java: " + projectFilePaths);
    }

    @Test
    void graphFilesAreConsistentWithManifest() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();
        ExecutionManifest manifest = success.manifest();

        // Index manifest files by relative path for cross-checking.
        Map<String, AppliedFile> manifestByPath = new LinkedHashMap<>();
        for (AppliedFile af : manifest.files()) {
            manifestByPath.put(af.relativePath(), af);
        }

        // For every ProjectFile node, verify path/artifactId/symbolId/sha256
        // match the manifest entry.
        List<ProjectGraphNode.ProjectFile> fileNodes = graph.nodes().stream()
                .filter(n -> n instanceof ProjectGraphNode.ProjectFile)
                .map(n -> (ProjectGraphNode.ProjectFile) n)
                .toList();
        assertEquals(manifestByPath.size(), fileNodes.size(),
                "graph file count must equal manifest file count");

        for (ProjectGraphNode.ProjectFile fileNode : fileNodes) {
            String path = fileNode.id().relativePath();
            AppliedFile applied = manifestByPath.get(path);
            assertNotNull(applied, "graph file path must exist in manifest: " + path);
            assertEquals(applied.sha256Hex(), fileNode.provenance().sha256Hex(),
                    "SHA-256 must match for " + path);
            assertEquals(applied.byteCount(), fileNode.provenance().byteCount(),
                    "byte count must match for " + path);
            assertEquals(applied.artifactId(), fileNode.provenance().artifactId(),
                    "artifactId must match for " + path);
            assertEquals(applied.symbolId(), fileNode.provenance().ownerSymbol(),
                    "ownerSymbol must match for " + path);
        }
    }

    @Test
    void allGraphPublicCollectionsAreImmutable() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();

        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().add(null),
                "nodes() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class, () -> graph.edges().add(null),
                "edges() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> graph.outgoing(GraphNodeId.ProjectNodeId.INSTANCE).add(null),
                "outgoing() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> graph.incoming(GraphNodeId.ProjectNodeId.INSTANCE).add(null),
                "incoming() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> graph.artifactsFor(USER_ENTITY_SYMBOL).add(null),
                "artifactsFor() must be unmodifiable");
        assertThrows(UnsupportedOperationException.class,
                () -> graph.filesForArtifact(new LoweredNodeId("any")).add(null),
                "filesForArtifact() must be unmodifiable");
    }

    @Test
    void repeatedRunsProduceSameCanonicalDigest() throws Exception {
        ToolchainResult.Success first = runCampusMarket();
        ToolchainResult.Success second = runCampusMarket();
        assertEquals(first.graph().canonicalDigest(), second.graph().canonicalDigest(),
                "canonical digest must be stable across repeated runs");
    }

    @Test
    void differentAbsoluteOutputRootsProduceSameCanonicalDigest() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRootA = temporaryDirectory.resolve("rootA").toAbsolutePath();
        Path outputRootB = temporaryDirectory.resolve("rootB").toAbsolutePath();

        ToolchainResult.Success a = ApplicationTestSupport.runCampusMarket(source, outputRootA);
        ToolchainResult.Success b = ApplicationTestSupport.runCampusMarket(source, outputRootB);
        assertEquals(a.graph().canonicalDigest(), b.graph().canonicalDigest(),
                "canonical digest must be independent of absolute output root");
    }

    @Test
    void provenanceSourceIdIsWorkspaceRelativeNotAbsolute() throws Exception {
        ToolchainResult.Success success = runCampusMarket();
        ProjectGraph graph = success.graph();
        // Every node's provenance sourceId must be the workspace-relative
        // SourceId ("campus-market.sir"), never an absolute path.
        for (ProjectGraphNode node : graph.nodes()) {
            String sourceIdValue = node.provenance().sourceId().value();
            assertFalse(sourceIdValue.contains(":") && sourceIdValue.contains("\\"),
                    "provenance sourceId must not be an absolute path: " + sourceIdValue
                            + " for node " + node.id());
            assertEquals("campus-market.sir", sourceIdValue,
                    "provenance sourceId must be the workspace-relative SIR id");
        }
    }

    @Test
    void projectArtifactOriginsArePreservedVerbatimFromLowering() throws Exception {
        SourceId sourceId = SourceId.of("campus-market.sir");
        ParseResult parse = SirParser.create().parse(new SirSource(
                sourceId, ApplicationTestSupport.resource("valid/campus-market.sir")));
        SemanticAnalysis semantic = new SirSemanticAnalyzer().analyze(
                parse.document().orElseThrow());
        NormalizedSemanticModel normalized = semantic.model().orElseThrow();
        LoweringAnalysis<SpringBootLoweredModel> lowering =
                new SpringBootTargetLowering().lower(normalized);
        SpringBootLoweredModel lowered = lowering.model().orElseThrow();
        GenerationResult.Success generation = assertInstanceOf(
                GenerationResult.Success.class,
                new SpringBootGenerator().generate(lowered));
        ProjectGraphInput input = new SpringBootProjectGraphInputFactory().build(
                normalized, lowered, generation.files(), sourceId);

        ProjectGraphInput.ArtifactInput mavenInput = input.artifacts().stream()
                .filter(a -> a.role() == ArtifactRole.ProjectRole.MAVEN_PROJECT)
                .findFirst().orElseThrow();
        ProjectGraphInput.ArtifactInput applicationInput = input.artifacts().stream()
                .filter(a -> a.role() == ArtifactRole.ProjectRole.APPLICATION_MAIN)
                .findFirst().orElseThrow();
        assertEquals(lowered.mavenProject().origin(), mavenInput.origin(),
                "factory must preserve MavenProject origin from the actual Lowered Model");
        assertEquals(lowered.applicationMain().origin(), applicationInput.origin(),
                "factory must preserve ApplicationMain origin from the actual Lowered Model");

        ProjectGraphAnalysis.Success graphSuccess = assertInstanceOf(
                ProjectGraphAnalysis.Success.class,
                new ProjectGraphBuilder().build(input));
        ProjectGraph graph = graphSuccess.graph();

        List<ProjectGraphNode.Artifact> projectArtifacts = graph.nodes().stream()
                .filter(n -> n instanceof ProjectGraphNode.Artifact a
                        && a.role() instanceof ArtifactRole.ProjectRole)
                .map(n -> (ProjectGraphNode.Artifact) n)
                .toList();
        // The project carries the target's own supporting artifacts beside the build file and the
        // entry point; which ones exist is the lowering's decision, so the graph must mirror it
        // exactly rather than a fixed pair.
        assertEquals(PROJECT_ROLES.size(), projectArtifacts.size(),
                "must mirror every project artifact the lowering emits (MavenProject, ApplicationMain, and "
                        + "the target's own support artifacts): " + projectArtifacts);

        Set<ArtifactRole> seenRoles = new HashSet<>();
        for (ProjectGraphNode.Artifact artifact : projectArtifacts) {
            GraphProvenance.ArtifactProvenance prov = artifact.provenance();
            // Each project artifact carries the origin of the lowered artifact behind it, looked up by
            // role rather than assumed from a two-file shape.
            LoweredOrigin expectedOrigin = expectedProjectOrigin(lowered, artifact.role());
            // graph-level sourceId is the SIR SourceId
            assertEquals(SourceId.of("campus-market.sir"), prov.sourceId(),
                    "graph-level sourceId must be the SIR SourceId for " + artifact.id());
            // ownerSymbol is empty
            assertTrue(prov.ownerSymbol().isEmpty(),
                    "project-level Artifact ownerSymbol must be empty for " + artifact.id());
            // origin is preserved verbatim from the lowering
            assertEquals(expectedOrigin, prov.origin(),
                    "Graph Artifact origin must equal its actual Lowered Model origin for "
                            + artifact.id());
            seenRoles.add(artifact.role());
        }
        assertTrue(seenRoles.contains(ArtifactRole.ProjectRole.MAVEN_PROJECT),
                "must include MAVEN_PROJECT: " + seenRoles);
        assertTrue(seenRoles.contains(ArtifactRole.ProjectRole.APPLICATION_MAIN),
                "must include APPLICATION_MAIN: " + seenRoles);
        assertEquals(PROJECT_ROLES, seenRoles, "every emitted project role must reach the graph");
    }

    /** The lowered artifact's origin for one project role, read from the lowered model itself. */
    private static LoweredOrigin expectedProjectOrigin(
            SpringBootLoweredModel lowered, ArtifactRole role) {
        return switch ((ArtifactRole.ProjectRole) role) {
            case MAVEN_PROJECT -> lowered.mavenProject().origin();
            case APPLICATION_MAIN -> lowered.applicationMain().origin();
            case PAGE_RESPONSE -> lowerProjectArtifact(lowered, ProjectArtifact.PageResponse.class);
            case API_ERROR_RESPONSE -> lowerProjectArtifact(lowered, ProjectArtifact.ApiErrorResponse.class);
            case API_EXCEPTION_BASE -> lowerProjectArtifact(lowered, ProjectArtifact.ApiExceptionBase.class);
            case API_EXCEPTION_ADVICE -> lowerProjectArtifact(lowered, ProjectArtifact.ApiExceptionAdvice.class);
            case VALIDATION_SUPPORT -> lowerProjectArtifact(lowered, ProjectArtifact.ValidationSupport.class);
            case APPLICATION_CONFIG -> lowerProjectArtifact(lowered, ProjectArtifact.ApplicationConfig.class);
        };
    }

    private static LoweredOrigin lowerProjectArtifact(
            SpringBootLoweredModel lowered, Class<? extends ProjectArtifact> kind) {
        return lowered.projectArtifacts().stream()
                .filter(kind::isInstance)
                .map(ProjectArtifact::origin)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the lowered model emits no " + kind.getSimpleName()));
    }

    private ToolchainResult.Success runCampusMarket() throws Exception {
        Path source = ApplicationTestSupport.writeCampusMarketSource(temporaryDirectory);
        Path outputRoot = temporaryDirectory.resolve("out-" + System.nanoTime()).toAbsolutePath();
        return ApplicationTestSupport.runCampusMarket(source, outputRoot);
    }

    private static String summarizeNodes(ProjectGraph graph) {
        Map<Class<?>, Integer> counts = new LinkedHashMap<>();
        for (ProjectGraphNode node : graph.nodes()) {
            counts.merge(node.getClass(), 1, Integer::sum);
        }
        return counts.toString();
    }

    private static String summarizeEdges(ProjectGraph graph) {
        Map<GraphEdgeKind, Integer> counts = new LinkedHashMap<>();
        for (ProjectGraphEdge edge : graph.edges()) {
            counts.merge(edge.kind(), 1, Integer::sum);
        }
        return counts.toString();
    }
}