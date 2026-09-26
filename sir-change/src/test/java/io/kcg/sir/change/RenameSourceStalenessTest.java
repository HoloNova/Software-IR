package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.ast.AstCapabilityDecl;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.RenameAnalysis;
import io.kcg.sir.change.api.RenameDiagnostic;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenamePlanRequest;
import io.kcg.sir.change.api.RenamePlanningInput;
import io.kcg.sir.change.api.RenamePlanner;
import io.kcg.sir.change.api.RenameRevisionSnapshot;
import io.kcg.sir.change.api.RenameSourceSnapshot;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A revision is a source <em>and</em> its graph (Q16 review gap 2).
 *
 * <p>Graph-only staleness checking misses the edit that changes no node, no edge and no digest: a
 * comment. Here the graph is held fixed on purpose — the point is that with the graph provably
 * unchanged, a different source still makes the plan invalid — and the real-pipeline test that
 * <em>measures</em> the unchanged graph lives in {@code sir-toolchain-application}'s
 * {@code RenamePlanVerticalTest}.
 */
class RenameSourceStalenessTest {

    private static final String BASE_NAME = "SearchCourseEnrollments";
    private static final String CANDIDATE_NAME = "SearchEnrollments";
    private static final String BASE_SOURCE = RenameTestFixtures.source(BASE_NAME);
    private static final String CANDIDATE_SOURCE = RenameTestFixtures.source(CANDIDATE_NAME);
    /** The base source with one equal-length comment replaced: the bytes differ, nothing else does. */
    private static final String EDITED_BASE_SOURCE = RenameTestFixtures.sourceWithOtherComment(BASE_NAME);

    private static final SymbolId SUBJECT = RenameTestFixtures.capability(
            RenameTestFixtures.model(RenameTestFixtures.source(BASE_NAME))).id();
    private static final LoweredNodeId SERVICE_ARTIFACT = new LoweredNodeId(
            "lir://spring/artifact-service/sir%3A%2F%2FRename%2Fdeclared%2Fcapability%2Fsearch-enrollments");
    private static final LoweredNodeId CONTROLLER_ARTIFACT = new LoweredNodeId(
            "lir://spring/artifact-controller/sir%3A%2F%2FRename%2Fdeclared%2Fcapability%2Fsearch-enrollments");

    private final RenamePlanner planner = new RenamePlanner();

    @Test
    void theCommentEditChangesOnlyTheSourceBytesAndMovesNoToken() {
        // A graph can only see positions, so this is the premise the source digest exists for: the
        // edit keeps every token where it was and still changes the bytes.
        SourceSpan baseSpan = capabilitySpan(BASE_SOURCE);
        SourceSpan editedSpan = capabilitySpan(EDITED_BASE_SOURCE);
        assertEquals(baseSpan, editedSpan, "an equal-length comment edit must not move any token");
        assertNotEquals(digestOf(BASE_SOURCE), digestOf(EDITED_BASE_SOURCE));
        assertEquals(BASE_SOURCE.length(), EDITED_BASE_SOURCE.length());
    }

    private static SourceSpan capabilitySpan(String sourceText) {
        ParseResult parsed = SirParser.create().parse(new SirSource(RenameTestFixtures.SOURCE_ID, sourceText));
        assertTrue(parsed.isSuccess(), () -> "fixture must parse: " + parsed.diagnostics());
        return parsed.document().orElseThrow().software().declarations().stream()
                .filter(AstCapabilityDecl.class::isInstance)
                .map(AstCapabilityDecl.class::cast)
                .findFirst()
                .orElseThrow()
                .span();
    }

    @Test
    void aPlanComputedForOneSourceIsStaleForAnotherSourceWithTheSameGraph() {
        RenamePlan plan = plan(BASE_SOURCE, CANDIDATE_SOURCE);

        List<RenameDiagnostic> diagnostics = planner.verify(
                plan,
                revision(EDITED_BASE_SOURCE, graph(declaredSymbol())),
                revision(CANDIDATE_SOURCE, graph(declaredSymbol(), CANDIDATE_NAME))
        );

        assertEquals(List.of("SIR-RENAME-STALE-004"), codes(diagnostics));
    }

    @Test
    void aCandidateOnlySourceEditAlsoMakesThePlanStale() {
        RenamePlan plan = plan(BASE_SOURCE, CANDIDATE_SOURCE);

        List<RenameDiagnostic> diagnostics = planner.verify(
                plan,
                revision(BASE_SOURCE, graph(declaredSymbol())),
                revision(CANDIDATE_SOURCE + "\n", graph(declaredSymbol(), CANDIDATE_NAME))
        );

        assertEquals(List.of("SIR-RENAME-STALE-005"), codes(diagnostics));
    }

    @Test
    void aRequestWhoseBaseSourceDigestIsNotTheInputSourceIsRejected() {
        ProjectGraph graph = graph(declaredSymbol());
        ChangeBaseRevision otherBytes = new ChangeBaseRevision(
                RenameTestFixtures.SOURCE_ID,
                digestOf(EDITED_BASE_SOURCE),
                GraphVersion.V0_1,
                graph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1
        );

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                RenameTestFixtures.model(BASE_SOURCE),
                RenameTestFixtures.model(CANDIDATE_SOURCE),
                revision(BASE_SOURCE, graph),
                revision(CANDIDATE_SOURCE, graph(declaredSymbol(), CANDIDATE_NAME)),
                new RenamePlanRequest(otherBytes, declaredSymbol())
        ));

        assertEquals(List.of("SIR-RENAME-REQUEST-004"), codes(analysis));
    }

    @Test
    void aSnapshotThatDoesNotDescribeItsGraphIsRejected() {
        ProjectGraph graph = graph(declaredSymbol());
        ChangeBaseRevision revision = new ChangeBaseRevision(
                RenameTestFixtures.SOURCE_ID,
                digestOf(BASE_SOURCE),
                GraphVersion.V0_1,
                graph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1
        );

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                RenameTestFixtures.model(BASE_SOURCE),
                RenameTestFixtures.model(CANDIDATE_SOURCE),
                new RenameRevisionSnapshot(graph, RenameSourceSnapshot.of(SourceId.of("somewhere-else.sir"), BASE_SOURCE)),
                revision(CANDIDATE_SOURCE, graph(declaredSymbol(), CANDIDATE_NAME)),
                new RenamePlanRequest(revision, declaredSymbol())
        ));

        assertEquals(List.of("SIR-RENAME-REQUEST-002"), codes(analysis));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static SymbolId declaredSymbol() {
        return SUBJECT;
    }

    private static String digestOf(String sourceText) {
        return RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, sourceText).sha256Hex();
    }

    private static ProjectGraph graph(SymbolId symbol) {
        return graph(symbol, BASE_NAME);
    }

    /**
     * The managed closure of one capability as the target would lower it: one service and one
     * controller artifact whose paths follow the declaration name, while their artifact ids follow the
     * declaration identity.
     */
    private static ProjectGraph graph(SymbolId symbol, String capabilityName) {
        String root = "src/main/java/com/example/rename/";
        return RenameTestFixtures.graph(symbol, capabilityName)
                .service(
                        SERVICE_ARTIFACT,
                        "com.example.rename.application." + capabilityName + "Service",
                        root + "application/" + capabilityName + "Service.java",
                        RenameTestFixtures.SHA_A
                )
                .controller(
                        CONTROLLER_ARTIFACT,
                        "com.example.rename.api." + capabilityName + "Controller",
                        root + "api/" + capabilityName + "Controller.java",
                        RenameTestFixtures.SHA_B
                )
                .build();
    }

    private static RenameRevisionSnapshot revision(String sourceText, ProjectGraph graph) {
        return new RenameRevisionSnapshot(graph, RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, sourceText));
    }

    private RenamePlan plan(String baseSource, String candidateSource) {
        ProjectGraph baseGraph = graph(declaredSymbol());
        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                RenameTestFixtures.model(baseSource),
                RenameTestFixtures.model(candidateSource),
                revision(baseSource, baseGraph),
                revision(candidateSource, graph(declaredSymbol(), CANDIDATE_NAME)),
                new RenamePlanRequest(
                        new ChangeBaseRevision(
                                RenameTestFixtures.SOURCE_ID,
                                digestOf(baseSource),
                                GraphVersion.V0_1,
                                baseGraph.canonicalDigest(),
                                ProjectGraphCanonicalFormatVersion.V1
                        ),
                        declaredSymbol()
                )
        ));

        assertInstanceOf(RenameAnalysis.Planned.class, analysis, () -> "expected a plan: " + codes(analysis));
        return ((RenameAnalysis.Planned) analysis).plan();
    }

    private static List<String> codes(RenameAnalysis analysis) {
        return analysis.diagnostics().stream().map(RenameDiagnostic::code).toList();
    }

    private static List<String> codes(List<RenameDiagnostic> diagnostics) {
        return diagnostics.stream().map(RenameDiagnostic::code).toList();
    }
}
