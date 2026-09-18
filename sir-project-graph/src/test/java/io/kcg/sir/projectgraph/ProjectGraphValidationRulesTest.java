package io.kcg.sir.projectgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphProvenance;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.ArtifactInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.EdgeBinding;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.FileInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.LoweredDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.SemanticDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ProjectGraphValidator;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * One test per {@link ProjectGraphValidator} rule, each perturbing exactly one aspect of the
 * {@link ProjectGraphFixtures#campusMarket()} fixture.
 *
 * <p>Every assertion names the exact set of diagnostic codes produced, so an accidental cascade or
 * a rule that silently stopped reporting fails the test instead of passing quietly. Where a
 * perturbation legitimately triggers two related rules, both codes are listed with the reason.
 * Failure must always be structured: no rule may escape as a runtime exception.
 */
class ProjectGraphValidationRulesTest {

    // ---- input-level rules ---------------------------------------------------------------------

    @Test
    void path001RejectsIllegalFileInputPath() {
        FileInput illegal = new FileInput("src/main/java/../User.java",
                ProjectGraphFixtures.artifactId(ProjectGraphFixtures.USER_ENTITY_SYMBOL,
                        ArtifactRole.DeclarationRole.ENTITY_MODEL),
                Optional.of(ProjectGraphFixtures.USER_ENTITY_SYMBOL), 3, ProjectGraphFixtures.sha256Hex(new byte[3]));
        ProjectGraphInput input = ProjectGraphFixtures.withFiles(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().files(), illegal));

        // validateInput runs before any graph is built, so only the input rule can report.
        assertCodes(build(input), "SIR-GRAPH-PATH-001");
    }

    // ---- node identity and node kind rules ------------------------------------------------------

    @Test
    void node001RejectsDuplicateNodeId() {
        SemanticDeclarationInput duplicate = ProjectGraphFixtures.first(
                ProjectGraphFixtures.campusMarket().semanticDeclarations(),
                declaration -> declaration.symbolId().equals(ProjectGraphFixtures.ENUM_SYMBOL));
        ProjectGraphInput input = ProjectGraphFixtures.withSemantics(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().semanticDeclarations(), duplicate));

        assertCodes(build(input), "SIR-GRAPH-NODE-001");
    }

    @Test
    void node002RejectsMissingProjectRoot() {
        ProjectGraph graph = validGraph();
        List<ProjectGraphNode> withoutProject = graph.nodes().stream()
                .filter(node -> !(node instanceof ProjectGraphNode.Project))
                .toList();

        // Removing the root also dangles every edge that started at it, and those edges can no
        // longer be checked for endpoint kind.
        assertCodes(validate(withoutProject, graph.edges()),
                "SIR-GRAPH-NODE-002", "SIR-GRAPH-EDGE-002", "SIR-GRAPH-EDGE-004");
    }

    @Test
    void node003RejectsMultipleProjectRoots() {
        ProjectGraph graph = validGraph();
        ProjectGraphNode project = ProjectGraphFixtures.first(graph.nodes(),
                node -> node instanceof ProjectGraphNode.Project);
        // Inserted directly after the existing root so that canonical node order is preserved and
        // only the duplicated identity and the second root are reported.
        List<ProjectGraphNode> twoRoots = new ArrayList<>(graph.nodes());
        twoRoots.add(1, project);

        assertCodes(validate(twoRoots, graph.edges()), "SIR-GRAPH-NODE-001", "SIR-GRAPH-NODE-003");
    }

    @Test
    void node004RejectsIllegalTopLevelSymbolKind() {
        SemanticDeclarationInput original = ProjectGraphFixtures.first(
                ProjectGraphFixtures.campusMarket().semanticDeclarations(),
                declaration -> declaration.symbolId().equals(ProjectGraphFixtures.USER_ENTITY_SYMBOL));
        SemanticDeclarationInput illegal = new SemanticDeclarationInput(original.symbolId(), SymbolKind.FIELD,
                original.displayName(), original.sourceNodeId(), original.span());
        ProjectGraphInput input = ProjectGraphFixtures.withSemantics(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.replace(ProjectGraphFixtures.campusMarket().semanticDeclarations(),
                        indexOfSemantic(original), illegal));

        assertCodes(build(input), "SIR-GRAPH-NODE-004");
    }

    // ---- edge identity, endpoint and arity rules -------------------------------------------------

    @Test
    void edge001RejectsDuplicateEdgeId() {
        EdgeBinding declares = ProjectGraphFixtures.first(ProjectGraphFixtures.campusMarket().edges(),
                edge -> edge.kind() == GraphEdgeKind.DECLARES);
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(), declares));

        assertCodes(build(input), "SIR-GRAPH-EDGE-001");
    }

    @Test
    void edge002RejectsDanglingEdge() {
        GraphNodeId.Semantic missing = ProjectGraphFixtures.semanticNode(
                ProjectGraphFixtures.symbol("entity", "Missing"));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(),
                        new EdgeBinding(GraphEdgeKind.DECLARES, ProjectGraphFixtures.PROJECT_ID, missing)));

        // An edge to an unknown node is both dangling and, because the target cannot be inspected,
        // of unverifiable endpoint kind.
        assertCodes(build(input), "SIR-GRAPH-EDGE-002", "SIR-GRAPH-EDGE-004");
    }

    @Test
    void edge003RejectsSelfLoop() {
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(),
                        new EdgeBinding(GraphEdgeKind.DECLARES, ProjectGraphFixtures.PROJECT_ID,
                                ProjectGraphFixtures.PROJECT_ID)));

        // A Project-to-Project edge is also a wrong endpoint kind for DECLARES.
        assertCodes(build(input), "SIR-GRAPH-EDGE-003", "SIR-GRAPH-EDGE-004");
    }

    @Test
    void edge004RejectsWrongEndpointKinds() {
        GraphNodeId.Semantic user = ProjectGraphFixtures.semanticNode(ProjectGraphFixtures.USER_ENTITY_SYMBOL);
        GraphNodeId.Lowered lowered = new GraphNodeId.Lowered(
                ProjectGraphFixtures.loweredId(ProjectGraphFixtures.USER_ENTITY_SYMBOL));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(),
                        new EdgeBinding(GraphEdgeKind.DECLARES, lowered, user)));

        assertCodes(build(input), "SIR-GRAPH-EDGE-004");
    }

    @Test
    void edge005RejectsTwoLowersToEdgesTargetingOneLoweredDeclaration() {
        GraphNodeId.Lowered goodsLowered = new GraphNodeId.Lowered(
                ProjectGraphFixtures.loweredId(ProjectGraphFixtures.GOODS_ENTITY_SYMBOL));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(),
                        new EdgeBinding(GraphEdgeKind.LOWERS_TO,
                                ProjectGraphFixtures.semanticNode(ProjectGraphFixtures.USER_ENTITY_SYMBOL),
                                goodsLowered)));

        // The User symbol now also lowers to the Goods declaration, which gives it two outgoing
        // LOWERS_TO edges as well.
        assertCodes(build(input), "SIR-GRAPH-EDGE-005", "SIR-GRAPH-EDGE-011");
    }

    @Test
    void edge006RejectsTwoOwnsArtifactEdgesTargetingOneArtifact() {
        GraphNodeId.Lowered enumArtifact = new GraphNodeId.Lowered(ProjectGraphFixtures.artifactId(
                ProjectGraphFixtures.ENUM_SYMBOL, ArtifactRole.DeclarationRole.ENUM));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(),
                        new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT,
                                new GraphNodeId.Lowered(ProjectGraphFixtures.loweredId(
                                        ProjectGraphFixtures.USER_ENTITY_SYMBOL)),
                                enumArtifact)));

        assertCodes(build(input), "SIR-GRAPH-EDGE-006");
    }

    @Test
    void edge007RejectsTwoGeneratesFileEdgesTargetingOneFile() {
        GraphNodeId.File userEntityFile = new GraphNodeId.File(ProjectGraphFixtures.relativePath("entity", "User"));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.append(ProjectGraphFixtures.campusMarket().edges(),
                        new EdgeBinding(GraphEdgeKind.GENERATES_FILE,
                                new GraphNodeId.Lowered(ProjectGraphFixtures.artifactId(
                                        ProjectGraphFixtures.USER_ENTITY_SYMBOL,
                                        ArtifactRole.DeclarationRole.MAPPER)),
                                userEntityFile)));

        assertCodes(build(input), "SIR-GRAPH-EDGE-007");
    }

    @Test
    void edge008RejectsSemanticDeclarationWithoutIncomingDeclaresEdge() {
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.drop(ProjectGraphFixtures.campusMarket().edges(),
                        edge -> edge.kind() == GraphEdgeKind.DECLARES
                                && edge.target().equals(ProjectGraphFixtures.semanticNode(
                                        ProjectGraphFixtures.ENUM_SYMBOL))));

        assertCodes(build(input), "SIR-GRAPH-EDGE-008");
    }

    @Test
    void edge010And012RejectSemanticDeclarationWithoutOutgoingLowersToEdge() {
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.drop(ProjectGraphFixtures.campusMarket().edges(),
                        edge -> edge.kind() == GraphEdgeKind.LOWERS_TO
                                && edge.source().equals(ProjectGraphFixtures.semanticNode(
                                        ProjectGraphFixtures.ENUM_SYMBOL))));

        // Dropping the edge also orphans the lowered declaration it used to target.
        assertCodes(build(input), "SIR-GRAPH-EDGE-010", "SIR-GRAPH-EDGE-012");
    }

    @Test
    void edge011RejectsTwoOutgoingLowersToEdgesFromOneSemanticDeclaration() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        LoweredNodeId extraLoweredId = new LoweredNodeId("lowered://extra/GoodsStatus");
        LoweredOrigin origin = ProjectGraphFixtures.origin(ProjectGraphFixtures.ENUM_SYMBOL,
                ProjectGraphFixtures.astId(ProjectGraphFixtures.ENUM_SYMBOL), ProjectGraphFixtures.span(80));
        ProjectGraphInput withExtra = ProjectGraphFixtures.withLowered(base,
                ProjectGraphFixtures.append(base.loweredDeclarations(),
                        new LoweredDeclarationInput(extraLoweredId, ProjectGraphFixtures.ENUM_SYMBOL,
                                "GoodsStatusExtra", origin)));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(withExtra,
                ProjectGraphFixtures.append(withExtra.edges(),
                        new EdgeBinding(GraphEdgeKind.LOWERS_TO,
                                ProjectGraphFixtures.semanticNode(ProjectGraphFixtures.ENUM_SYMBOL),
                                new GraphNodeId.Lowered(extraLoweredId))));

        assertCodes(build(input), "SIR-GRAPH-EDGE-011");
    }

    @Test
    void edge012RejectsLoweredDeclarationWithoutIncomingLowersToEdge() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        LoweredOrigin origin = ProjectGraphFixtures.origin(ProjectGraphFixtures.ERROR_SYMBOL,
                ProjectGraphFixtures.astId(ProjectGraphFixtures.ERROR_SYMBOL), ProjectGraphFixtures.span(90));
        ProjectGraphInput input = ProjectGraphFixtures.withLowered(base,
                ProjectGraphFixtures.append(base.loweredDeclarations(),
                        new LoweredDeclarationInput(new LoweredNodeId("lowered://orphan"), ProjectGraphFixtures.ERROR_SYMBOL,
                                "OrphanDeclaration", origin)));

        assertCodes(build(input), "SIR-GRAPH-EDGE-012");
    }

    @Test
    void edge013RejectsArtifactWithoutIncomingOwnsArtifactEdge() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        LoweredNodeId orphanArtifact = new LoweredNodeId("artifact://orphan");
        ProjectGraphInput input = ProjectGraphFixtures.withArtifacts(base,
                ProjectGraphFixtures.append(base.artifacts(),
                        new ArtifactInput(orphanArtifact, Optional.of(ProjectGraphFixtures.ERROR_SYMBOL),
                                ProjectGraphFixtures.origin(ProjectGraphFixtures.ERROR_SYMBOL,
                                        ProjectGraphFixtures.astId(ProjectGraphFixtures.ERROR_SYMBOL),
                                        ProjectGraphFixtures.span(90)),
                                ArtifactRole.DeclarationRole.EXCEPTION, "com.example.campusmarket.exception.Orphan")));

        assertCodes(build(input), "SIR-GRAPH-EDGE-013");
    }

    @Test
    void edge014RejectsProjectFileWithoutIncomingGeneratesFileEdge() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        String orphanPath = ProjectGraphFixtures.relativePath("dto", "Orphan");
        ProjectGraphInput input = ProjectGraphFixtures.withFiles(base,
                ProjectGraphFixtures.append(base.files(), ProjectGraphFixtures.fileInput(orphanPath,
                        ProjectGraphFixtures.artifactId(ProjectGraphFixtures.INPUT_SYMBOL,
                                ArtifactRole.DeclarationRole.REQUEST_DTO),
                        Optional.of(ProjectGraphFixtures.INPUT_SYMBOL))));

        assertCodes(build(input), "SIR-GRAPH-EDGE-014");
    }

    // ---- provenance rules ------------------------------------------------------------------------

    /**
     * {@code SIR-GRAPH-PROVENANCE-001} ("provenance variant does not match node kind") cannot be
     * triggered through the public API: each node record declares its provenance component with the
     * concrete provenance record type, so the compiler and the canonical constructor reject a
     * mismatch. This test pins that guarantee directly and records the validator rule as a
     * defensive re-check for materialized or decoded data rather than a reachable input mistake.
     */
    @Test
    void provenanceVariantIsEnforcedByTheNodeRecordTypes() {
        assertProvenanceComponent(ProjectGraphNode.Project.class, GraphProvenance.ProjectProvenance.class);
        assertProvenanceComponent(ProjectGraphNode.SemanticDeclaration.class,
                GraphProvenance.SemanticProvenance.class);
        assertProvenanceComponent(ProjectGraphNode.LoweredDeclaration.class,
                GraphProvenance.LoweredProvenance.class);
        assertProvenanceComponent(ProjectGraphNode.Artifact.class, GraphProvenance.ArtifactProvenance.class);
        assertProvenanceComponent(ProjectGraphNode.ProjectFile.class, GraphProvenance.FileProvenance.class);
    }

    private static void assertProvenanceComponent(Class<?> nodeType, Class<?> expectedProvenanceType) {
        java.lang.reflect.RecordComponent component = java.util.Arrays
                .stream(nodeType.getRecordComponents())
                .filter(candidate -> candidate.getName().equals("provenance"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nodeType + " has no provenance component"));
        assertEquals(expectedProvenanceType, component.getType(),
                nodeType.getSimpleName() + " must declare its exact provenance record type");
    }

    @Test
    void provenance002RejectsArtifactNodeFieldsThatDisagreeWithProvenance() {
        ProjectGraph graph = validGraph();
        GraphNodeId.Lowered artifactId = new GraphNodeId.Lowered(ProjectGraphFixtures.artifactId(
                ProjectGraphFixtures.USER_ENTITY_SYMBOL, ArtifactRole.DeclarationRole.MAPPER));
        ProjectGraphNode.Artifact original = (ProjectGraphNode.Artifact) graph.node(artifactId).orElseThrow();
        ProjectGraphNode.Artifact broken = new ProjectGraphNode.Artifact(original.id(), original.provenance(),
                ArtifactRole.DeclarationRole.SERVICE, original.qualifiedName());

        assertCodes(validate(ProjectGraphFixtures.replace(graph.nodes(), indexOfNode(graph, artifactId), broken),
                graph.edges()), "SIR-GRAPH-PROVENANCE-002");
    }

    @Test
    void provenance003RejectsLoweredSourceSymbolThatDisagreesWithItsLowersToSource() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        int index = indexOfLowered(base, ProjectGraphFixtures.ENUM_SYMBOL);
        LoweredDeclarationInput original = base.loweredDeclarations().get(index);
        LoweredDeclarationInput broken = new LoweredDeclarationInput(original.nodeId(),
                ProjectGraphFixtures.GOODS_ENTITY_SYMBOL, original.displayName(), original.origin());

        // Changing the source symbol also makes every artifact owned by that lowered declaration
        // disagree with its owning declaration.
        assertCodes(build(ProjectGraphFixtures.withLowered(base, ProjectGraphFixtures.replace(
                base.loweredDeclarations(), index, broken))),
                "SIR-GRAPH-PROVENANCE-003", "SIR-GRAPH-PROVENANCE-004");
    }

    @Test
    void provenance004RejectsProjectLevelArtifactWithOwnerSymbol() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        int index = indexOfProjectArtifact(base, ArtifactRole.ProjectRole.MAVEN_PROJECT);
        ArtifactInput original = base.artifacts().get(index);
        ArtifactInput broken = new ArtifactInput(original.artifactId(),
                Optional.of(ProjectGraphFixtures.ENUM_SYMBOL),
                new LoweredOrigin(Optional.of(ProjectGraphFixtures.ENUM_SYMBOL), original.origin().sourceNodeId(),
                        original.origin().span()),
                original.role(), original.qualifiedName());

        assertCodes(build(ProjectGraphFixtures.withArtifacts(base,
                ProjectGraphFixtures.replace(base.artifacts(), index, broken))), "SIR-GRAPH-PROVENANCE-004");
    }

    @Test
    void provenance004RejectsDeclarationArtifactOwnerThatDisagreesWithOwningDeclaration() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        int index = indexOfArtifact(base, ProjectGraphFixtures.USER_ENTITY_SYMBOL,
                ArtifactRole.DeclarationRole.MAPPER);
        ArtifactInput original = base.artifacts().get(index);
        ArtifactInput broken = new ArtifactInput(original.artifactId(), Optional.of(ProjectGraphFixtures.ENUM_SYMBOL),
                original.origin(), original.role(), original.qualifiedName());

        assertCodes(build(ProjectGraphFixtures.withArtifacts(base,
                ProjectGraphFixtures.replace(base.artifacts(), index, broken))), "SIR-GRAPH-PROVENANCE-004");
    }

    @Test
    void provenance005RejectsDuplicateArtifactOwnerPlusRole() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        int index = indexOfArtifact(base, ProjectGraphFixtures.USER_ENTITY_SYMBOL,
                ArtifactRole.DeclarationRole.MAPPER);
        ArtifactInput original = base.artifacts().get(index);
        LoweredNodeId secondMapperId = new LoweredNodeId("artifact://duplicate/mapper/User");
        String secondPath = ProjectGraphFixtures.relativePath("mapper", "UserMapperDuplicate");

        ProjectGraphInput withArtifact = ProjectGraphFixtures.withArtifacts(base,
                ProjectGraphFixtures.append(base.artifacts(), new ArtifactInput(secondMapperId,
                        original.ownerSymbol(), original.origin(), original.role(), original.qualifiedName())));
        ProjectGraphInput withFile = ProjectGraphFixtures.withFiles(withArtifact,
                ProjectGraphFixtures.append(withArtifact.files(), ProjectGraphFixtures.fileInput(secondPath,
                        secondMapperId, original.ownerSymbol())));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(withFile,
                ProjectGraphFixtures.append(withFile.edges(),
                        new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, new GraphNodeId.Lowered(
                                ProjectGraphFixtures.loweredId(ProjectGraphFixtures.USER_ENTITY_SYMBOL)),
                                new GraphNodeId.Lowered(secondMapperId))));
        ProjectGraphInput withGeneratesEdge = ProjectGraphFixtures.withEdges(input,
                ProjectGraphFixtures.append(input.edges(), new EdgeBinding(GraphEdgeKind.GENERATES_FILE,
                        new GraphNodeId.Lowered(secondMapperId), new GraphNodeId.File(secondPath))));

        assertCodes(build(withGeneratesEdge), "SIR-GRAPH-PROVENANCE-005");
    }

    @Test
    void provenance006RejectsProvenanceSourceIdThatDisagreesWithTheProjectRoot() {
        ProjectGraph graph = validGraph();
        GraphNodeId.Semantic user = ProjectGraphFixtures.semanticNode(ProjectGraphFixtures.USER_ENTITY_SYMBOL);
        SourceId other = SourceId.of("other.sir");
        ProjectGraphNode broken = new ProjectGraphNode.SemanticDeclaration((GraphNodeId.Semantic) user,
                new GraphProvenance.SemanticProvenance(other, ProjectGraphFixtures.astId(
                        ProjectGraphFixtures.USER_ENTITY_SYMBOL), ProjectGraphFixtures.span(0)),
                SymbolKind.ENTITY, "User");

        assertCodes(validate(ProjectGraphFixtures.replace(graph.nodes(), indexOfNode(graph, user), broken),
                graph.edges()), "SIR-GRAPH-PROVENANCE-006");
    }

    @Test
    void provenance007RejectsSpanFromAnotherSource() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        SemanticDeclarationInput original = ProjectGraphFixtures.first(base.semanticDeclarations(),
                declaration -> declaration.symbolId().equals(ProjectGraphFixtures.GOODS_ENTITY_SYMBOL));
        SourceSpan foreign = new SourceSpan(SourceId.of("other.sir"), original.span().start(), original.span().end());
        SemanticDeclarationInput broken = new SemanticDeclarationInput(original.symbolId(), original.kind(),
                original.displayName(), original.sourceNodeId(), foreign);

        assertCodes(build(ProjectGraphFixtures.withSemantics(base,
                ProjectGraphFixtures.replace(base.semanticDeclarations(), indexOfSemantic(original), broken))),
                "SIR-GRAPH-PROVENANCE-007");
    }

    // ---- path, order and version rules -----------------------------------------------------------

    @Test
    void path002RejectsCaseCollidingProjectFilePaths() {
        ProjectGraphInput base = ProjectGraphFixtures.campusMarket();
        LoweredNodeId enumArtifact = ProjectGraphFixtures.artifactId(ProjectGraphFixtures.ENUM_SYMBOL,
                ArtifactRole.DeclarationRole.ENUM);
        String collidingPath = ProjectGraphFixtures.relativePath("enums", "goodsstatus");

        ProjectGraphInput withFile = ProjectGraphFixtures.withFiles(base,
                ProjectGraphFixtures.append(base.files(),
                        ProjectGraphFixtures.fileInput(collidingPath, enumArtifact,
                                Optional.of(ProjectGraphFixtures.ENUM_SYMBOL))));
        ProjectGraphInput input = ProjectGraphFixtures.withEdges(withFile,
                ProjectGraphFixtures.append(withFile.edges(), new EdgeBinding(GraphEdgeKind.GENERATES_FILE,
                        new GraphNodeId.Lowered(enumArtifact), new GraphNodeId.File(collidingPath))));

        assertCodes(build(input), "SIR-GRAPH-PATH-002");
    }

    @Test
    void order001RejectsNodesThatAreNotInCanonicalOrder() {
        ProjectGraph graph = validGraph();
        List<ProjectGraphNode> reversed = new ArrayList<>(graph.nodes());
        java.util.Collections.reverse(reversed);

        assertCodes(validate(reversed, graph.edges()), "SIR-GRAPH-ORDER-001");
    }

    @Test
    void order002RejectsEdgesThatAreNotInCanonicalOrder() {
        ProjectGraph graph = validGraph();
        List<ProjectGraphEdge> reversed = new ArrayList<>(graph.edges());
        java.util.Collections.reverse(reversed);

        assertCodes(validate(graph.nodes(), reversed), "SIR-GRAPH-ORDER-002");
    }

    @Test
    void version001RejectsNullGraphVersion() {
        ProjectGraph graph = validGraph();
        List<ProjectGraphDiagnostic> diagnostics = new ProjectGraphValidator().validate(null, graph.nodes(),
                graph.edges());

        assertEquals(Set.of("SIR-GRAPH-VERSION-001"),
                diagnostics.stream().map(ProjectGraphDiagnostic::code).collect(Collectors.toCollection(TreeSet::new)),
                () -> diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(ProjectGraphDiagnostic::isError));
    }

    @Test
    void builderNeverReturnsAPartialGraphOnInvalidInput() {
        ProjectGraphInput invalid = ProjectGraphFixtures.withEdges(ProjectGraphFixtures.campusMarket(),
                ProjectGraphFixtures.drop(ProjectGraphFixtures.campusMarket().edges(),
                        edge -> edge.kind() == GraphEdgeKind.OWNS_ARTIFACT));

        ProjectGraphAnalysis analysis = build(invalid);

        ProjectGraphAnalysis.Failure failure = assertInstanceOf(ProjectGraphAnalysis.Failure.class, analysis,
                "invalid input must fail instead of producing a graph: " + analysis);
        assertTrue(failure.diagnostics().stream().anyMatch(ProjectGraphDiagnostic::isError));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static ProjectGraphInput campusMarket() {
        return ProjectGraphFixtures.campusMarket();
    }

    private static ProjectGraphAnalysis build(ProjectGraphInput input) {
        return new ProjectGraphBuilder().build(input);
    }

    private static List<ProjectGraphDiagnostic> validate(List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
        return new ProjectGraphValidator().validate(GraphVersion.V0_1, nodes, edges);
    }

    private static ProjectGraph validGraph() {
        return assertInstanceOf(ProjectGraphAnalysis.Success.class, build(campusMarket())).graph();
    }

    private static void assertCodes(ProjectGraphAnalysis analysis, String... expectedCodes) {
        assertCodes(analysis.diagnostics(), expectedCodes);
    }

    private static void assertCodes(List<ProjectGraphDiagnostic> diagnostics, String... expectedCodes) {
        Set<String> actual = diagnostics.stream()
                .map(ProjectGraphDiagnostic::code)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(List.of(expectedCodes)), actual, () -> "unexpected diagnostics: " + diagnostics);
    }

    private static int indexOfSemantic(SemanticDeclarationInput declaration) {
        return campusMarket().semanticDeclarations().indexOf(declaration);
    }

    private static int indexOfLowered(ProjectGraphInput input, SymbolId sourceSymbol) {
        for (int i = 0; i < input.loweredDeclarations().size(); i++) {
            if (input.loweredDeclarations().get(i).sourceSymbol().equals(sourceSymbol)) {
                return i;
            }
        }
        throw new AssertionError("no lowered declaration for " + sourceSymbol);
    }

    private static int indexOfArtifact(ProjectGraphInput input, SymbolId owner, ArtifactRole role) {
        for (int i = 0; i < input.artifacts().size(); i++) {
            ArtifactInput artifact = input.artifacts().get(i);
            if (artifact.ownerSymbol().equals(Optional.of(owner)) && artifact.role() == role) {
                return i;
            }
        }
        throw new AssertionError("no artifact for " + owner + " / " + role);
    }

    private static int indexOfProjectArtifact(ProjectGraphInput input, ArtifactRole role) {
        for (int i = 0; i < input.artifacts().size(); i++) {
            if (input.artifacts().get(i).role() == role) {
                return i;
            }
        }
        throw new AssertionError("no project artifact for " + role);
    }

    private static int indexOfNode(ProjectGraph graph, GraphNodeId id) {
        for (int i = 0; i < graph.nodes().size(); i++) {
            if (graph.nodes().get(i).id().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("no node for " + id);
    }
}
