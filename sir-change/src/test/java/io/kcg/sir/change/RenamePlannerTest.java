package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.RenameAnalysis;
import io.kcg.sir.change.api.RenameDiagnostic;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenamePlanRequest;
import io.kcg.sir.change.api.RenamePlanningInput;
import io.kcg.sir.change.api.RenamePlanner;
import io.kcg.sir.change.api.RenameRevisionSnapshot;
import io.kcg.sir.change.api.RenameSourceSnapshot;
import io.kcg.sir.change.api.RenameSubject;
import io.kcg.sir.change.api.RenameSubjectKind;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rename plan contract (Q16): what a rename is, what proves it, and what is refused.
 *
 * <p>A valid plan needs a declared identity on both sides, a one-to-one artifact mapping and three
 * file sets derived from the two managed closures. Everything else is a specific rejection —
 * name-derived identities, a candidate that dropped its {@code @id}, artifact sets that do not line
 * up, a path a foreign declaration already owns, a stale base revision — because "the names look
 * similar" is not evidence and the planner is not allowed to guess.
 */
class RenamePlannerTest {
    private static final String BASE_NAME = "SearchCourseEnrollments";
    private static final String CANDIDATE_NAME = "SearchEnrollments";

    private static final LoweredNodeId SERVICE_ARTIFACT = new LoweredNodeId("lir://spring/service/search-enrollments");
    private static final LoweredNodeId CONTROLLER_ARTIFACT = new LoweredNodeId("lir://spring/controller/search-enrollments");
    private static final LoweredNodeId FOREIGN_ARTIFACT = new LoweredNodeId("lir://spring/service/catalog");
    private static final SymbolId FOREIGN_SYMBOL = new SymbolId("sir://Rename/capability/SearchCatalog");

    private static final String SERVICE_QUALIFIED = "com.example.rename.application." + BASE_NAME + "Service";
    private static final String CONTROLLER_QUALIFIED = "com.example.rename.api." + BASE_NAME + "Controller";

    private final RenamePlanner planner = new RenamePlanner();

    // ---- the valid case ------------------------------------------------------------------------

    @Test
    void plansARenameWhenTheDeclaredIdentityAndTheManagedFilesLineUp() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph, declaredSymbol()));

        RenamePlan plan = plan(analysis);
        assertEquals(RenameSubjectKind.CAPABILITY, plan.subject().kind());
        assertEquals(declaredSymbol(), plan.subject().declarationSymbol());
        assertEquals(BASE_NAME, plan.subject().baseName());
        assertEquals(CANDIDATE_NAME, plan.subject().candidateName());
        assertEquals(List.of(), plan.updates());
        assertEquals(
                List.of(RenameTestFixtures.CONTROLLER_PATH_BASE, RenameTestFixtures.SERVICE_PATH_BASE),
                plan.withdrawals().stream().map(value -> value.relativePath()).toList());
        assertEquals(
                List.of(RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SERVICE_PATH_SURVIVING),
                plan.establishments().stream().map(value -> value.relativePath()).toList());
        assertEquals(baseGraph.canonicalDigest(), plan.baseGraphCanonicalDigest());
        assertEquals(candidateGraph.canonicalDigest(), plan.candidateGraphCanonicalDigest());
        assertEquals(List.of(), planner.verify(plan, base(baseGraph), candidate(candidateGraph)));
    }

    @Test
    void onePlanCanCarryAllThreeFileOutcomes() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        // The service file survives at the same path with different content; the controller moves.
        ProjectGraph candidateGraph = RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_C)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_D)
                .build();

        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));

        assertEquals(
                List.of(RenameTestFixtures.SERVICE_PATH_BASE),
                plan.updates().stream().map(value -> value.relativePath()).toList());
        assertEquals(
                List.of(RenameTestFixtures.CONTROLLER_PATH_BASE),
                plan.withdrawals().stream().map(value -> value.relativePath()).toList());
        assertEquals(
                List.of(RenameTestFixtures.CONTROLLER_PATH_CANDIDATE),
                plan.establishments().stream().map(value -> value.relativePath()).toList());
        assertEquals(RenameTestFixtures.SHA_A, plan.updates().getFirst().baseSha256Hex());
        assertEquals(RenameTestFixtures.SHA_C, plan.updates().getFirst().candidateSha256Hex());
    }

    @Test
    void planningTheSameInputsTwiceProducesTheSameDigestAndTheSameSets() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());

        RenamePlan first = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));
        RenamePlan second = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));

        assertEquals(first, second);
        assertEquals(first.planDigest(), second.planDigest());
    }

    @Test
    void aRequestForAnUnchangedNameIsNotARename() {
        NormalizedSemanticModel base = RenameTestFixtures.model(RenameTestFixtures.source(BASE_NAME));
        ProjectGraph baseGraph = baseGraph(declaredSymbol());

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                base, base, base(baseGraph), base(baseGraph), requestFor(baseGraph, declaredSymbol())
        ));

        assertInstanceOf(RenameAnalysis.NoRename.class, analysis);
        assertEquals(List.of(), analysis.diagnostics());
    }

    // ---- identity and target rejections ---------------------------------------------------------

    @Test
    void aNameDerivedIdentityCannotProveARename() {
        NormalizedSemanticModel legacyBase = RenameTestFixtures.model(RenameTestFixtures.sourceWithoutDeclaredIds(BASE_NAME));
        SymbolId legacySymbol = RenameTestFixtures.capability(legacyBase).id();
        NormalizedSemanticModel legacyCandidate = RenameTestFixtures.model(RenameTestFixtures.sourceWithoutDeclaredIds(CANDIDATE_NAME));
        ProjectGraph legacyBaseGraph = RenameTestFixtures.graph(legacySymbol, BASE_NAME).build();
        ProjectGraph legacyCandidateGraph = RenameTestFixtures.graph(legacySymbol, CANDIDATE_NAME).build();

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                legacyBase,
                legacyCandidate,
                new RenameRevisionSnapshot(legacyBaseGraph, RenameSourceSnapshot.of(
                        RenameTestFixtures.SOURCE_ID, RenameTestFixtures.sourceWithoutDeclaredIds(BASE_NAME))),
                new RenameRevisionSnapshot(legacyCandidateGraph, RenameSourceSnapshot.of(
                        RenameTestFixtures.SOURCE_ID, RenameTestFixtures.sourceWithoutDeclaredIds(CANDIDATE_NAME))),
                new RenamePlanRequest(
                        new ChangeBaseRevision(
                                RenameTestFixtures.SOURCE_ID,
                                sourceDigest(RenameTestFixtures.sourceWithoutDeclaredIds(BASE_NAME)),
                                GraphVersion.V0_1,
                                legacyBaseGraph.canonicalDigest(),
                                ProjectGraphCanonicalFormatVersion.V1
                        ),
                        legacySymbol
                )
        ));

        assertEquals(List.of("SIR-RENAME-IDENTITY-001"), codes(analysis));
    }

    @Test
    void aCandidateThatDroppedItsDeclaredIdIsRejectedInsteadOfGuessed() {
        NormalizedSemanticModel base = RenameTestFixtures.model(RenameTestFixtures.source(BASE_NAME));
        NormalizedSemanticModel candidate = RenameTestFixtures.model(RenameTestFixtures.sourceWithoutDeclaredIds(CANDIDATE_NAME));
        ProjectGraph baseGraph = baseGraph(declaredSymbol());

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                base, candidate, base(baseGraph), candidate(movedGraph(declaredSymbol())), requestFor(baseGraph, declaredSymbol())
        ));

        assertEquals(List.of("SIR-RENAME-IDENTITY-002"), codes(analysis));
    }

    @Test
    void aBaseWithoutThatIdentityIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                RenameTestFixtures.model(RenameTestFixtures.sourceWithoutDeclaredIds(BASE_NAME)),
                RenameTestFixtures.model(RenameTestFixtures.source(CANDIDATE_NAME)),
                base(baseGraph),
                candidate(movedGraph(declaredSymbol())),
                requestFor(baseGraph, declaredSymbol())
        ));

        assertEquals(List.of("SIR-RENAME-TARGET-001"), codes(analysis));
    }

    @Test
    void anEntityFieldRenameIsRejectedBecauseItsPhysicalColumnCannotFollow() {
        NormalizedSemanticModel model = RenameTestFixtures.model(RenameTestFixtures.source(BASE_NAME));
        SymbolId fieldSymbol = RenameTestFixtures.entityField(model).id();
        ProjectGraph baseGraph = baseGraph(fieldSymbol);

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                model, model, base(baseGraph), candidate(movedGraph(fieldSymbol)), requestFor(baseGraph, fieldSymbol)
        ));

        assertEquals(List.of("SIR-RENAME-PHYSICAL-001"), codes(analysis));
    }

    // ---- mapping and path rejections -----------------------------------------------------------

    @Test
    void artifactSetsThatAreNotOneToOneAreRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_SURVIVING, RenameTestFixtures.SHA_C)
                .build();

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph, declaredSymbol()));

        assertEquals(List.of("SIR-RENAME-MAP-001"), codes(analysis));
    }

    @Test
    void anArtifactThatChangedIdentityIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_SURVIVING, RenameTestFixtures.SHA_C)
                .controller(new LoweredNodeId("lir://spring/proxy/search-enrollments"), CONTROLLER_QUALIFIED,
                        RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_D)
                .build();

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph, declaredSymbol()));

        assertEquals(List.of("SIR-RENAME-MAP-002"), codes(analysis));
    }

    @Test
    void aNewPathThatAForeignDeclarationAlreadyOwnsIsRejected() {
        ProjectGraph baseGraph = RenameTestFixtures.graph(declaredSymbol(), BASE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_A)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_BASE, RenameTestFixtures.SHA_B)
                // Another declaration already owns the path this rename wants to establish.
                .foreignOwner(FOREIGN_ARTIFACT, FOREIGN_SYMBOL, "SearchCatalog",
                        RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_C)
                .build();
        ProjectGraph candidateGraph = RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_SURVIVING, RenameTestFixtures.SHA_C)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_D)
                .build();

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph, declaredSymbol()));
        List<String> codes = codes(analysis);

        assertTrue(codes.contains("SIR-RENAME-PATH-002"), codes.toString());
        // The base also holds that path, and the candidate does not, so the path is a managed-file
        // withdrawal the plan does not cover either; both facts name the same path.
        assertTrue(codes.contains("SIR-RENAME-PATH-004"), codes.toString());
        assertTrue(analysis.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.relativePath().orElse("").equals(RenameTestFixtures.CONTROLLER_PATH_CANDIDATE)));
    }

    /**
     * A graph cannot lie about who generated a path: the graph validator rejects a file whose
     * provenance artifact disagrees with its GENERATES_FILE source (SIR-GRAPH-PROVENANCE-004). That
     * is why the plan needs no ownership check on top: every path it withdraws or updates came out of
     * the base closure, hence out of the base managed manifest.
     */
    @Test
    void everyPathAPlanNamesIsEitherBaseManagedOrAbsentFromTheBaseManifest() {
        ProjectGraph baseGraph = RenameTestFixtures.graph(declaredSymbol(), BASE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_A)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_BASE, RenameTestFixtures.SHA_B)
                .build();
        ProjectGraph candidateGraph = RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_C)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_D)
                .build();
        java.util.Set<String> baseManaged = baseGraph.nodes().stream()
                .filter(io.kcg.sir.projectgraph.api.ProjectGraphNode.ProjectFile.class::isInstance)
                .map(node -> ((io.kcg.sir.projectgraph.api.ProjectGraphNode.ProjectFile) node).id().relativePath())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));

        assertTrue(plan.updates().stream().allMatch(value -> baseManaged.contains(value.relativePath())));
        assertTrue(plan.withdrawals().stream().allMatch(value -> baseManaged.contains(value.relativePath())));
        assertTrue(plan.establishments().stream().noneMatch(value -> baseManaged.contains(value.relativePath())));
    }

    // ---- revision rejections --------------------------------------------------------------------

    @Test
    void aStaleBaseRevisionIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());
        ChangeBaseRevision stale = new ChangeBaseRevision(
                RenameTestFixtures.SOURCE_ID, declaredBaseDigest(), GraphVersion.V0_1, "f".repeat(64), ProjectGraphCanonicalFormatVersion.V1
        );

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                RenameTestFixtures.model(BASE_SOURCE),
                RenameTestFixtures.model(CANDIDATE_SOURCE),
                base(baseGraph),
                candidate(candidateGraph),
                new RenamePlanRequest(stale, declaredSymbol())
        ));

        assertEquals(List.of("SIR-RENAME-REQUEST-003"), codes(analysis));
    }

    @Test
    void aRequestForAnotherSourceOrSnapshotFormatIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());
        ChangeBaseRevision otherSource = new ChangeBaseRevision(
                io.kcg.sir.source.SourceId.of("other.sir"),
                declaredBaseDigest(),
                GraphVersion.V0_1,
                baseGraph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1
        );

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                RenameTestFixtures.model(BASE_SOURCE),
                RenameTestFixtures.model(CANDIDATE_SOURCE),
                base(baseGraph),
                candidate(candidateGraph),
                new RenamePlanRequest(otherSource, declaredSymbol())
        ));

        assertEquals(List.of("SIR-RENAME-REQUEST-002"), codes(analysis));
    }

    // ---- staleness of an existing plan ----------------------------------------------------------

    @Test
    void verifyingAPlanAgainstAChangedCandidateGraphIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());
        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));
        ProjectGraph changedCandidate = RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_SURVIVING, RenameTestFixtures.SHA_D)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_D)
                .build();

        List<RenameDiagnostic> diagnostics = planner.verify(plan, base(baseGraph), candidate(changedCandidate));

        assertTrue(codes(diagnostics).contains("SIR-RENAME-STALE-002"), codes(diagnostics).toString());
        // The plan also no longer describes that revision's files, and it says which one.
        assertTrue(codes(diagnostics).contains("SIR-RENAME-PATH-004"), codes(diagnostics).toString());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code().equals("SIR-RENAME-PATH-004")
                        && diagnostic.relativePath().orElse("").equals(RenameTestFixtures.SERVICE_PATH_SURVIVING)));
    }

    @Test
    void verifyingAPlanAgainstAChangedBaseGraphIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());
        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));
        ProjectGraph changedBase = RenameTestFixtures.graph(declaredSymbol(), BASE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_C)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_BASE, RenameTestFixtures.SHA_B)
                .build();

        List<RenameDiagnostic> diagnostics = planner.verify(plan, base(changedBase), candidate(candidateGraph));

        assertEquals(2, codes(diagnostics).stream().filter("SIR-RENAME-STALE-001"::equals).count(), codes(diagnostics).toString());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code().equals("SIR-RENAME-PATH-004")
                        && diagnostic.relativePath().orElse("").equals(RenameTestFixtures.SERVICE_PATH_BASE)));
    }

    @Test
    void verifyingAPlanWhoseBaseFileDigestMovedIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());
        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));
        ProjectGraph sameShapeOtherContent = RenameTestFixtures.graph(declaredSymbol(), BASE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_D)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_BASE, RenameTestFixtures.SHA_B)
                .build();

        List<RenameDiagnostic> diagnostics = planner.verify(plan, base(sameShapeOtherContent), candidate(candidateGraph));

        assertTrue(codes(diagnostics).contains("SIR-RENAME-STALE-001"), codes(diagnostics).toString());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.relativePath().orElse("").equals(RenameTestFixtures.SERVICE_PATH_BASE)));
    }

    @Test
    void verifyingATamperedPlanDigestIsRejected() {
        ProjectGraph baseGraph = baseGraph(declaredSymbol());
        ProjectGraph candidateGraph = movedGraph(declaredSymbol());
        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph, declaredSymbol())));
        RenamePlan tampered = new RenamePlan(
                plan.basedOn(),
                plan.subject(),
                plan.updates(),
                plan.withdrawals(),
                List.of(),
                plan.baseGraphCanonicalDigest(),
                plan.candidateGraphCanonicalDigest(),
                plan.baseSourceSha256Hex(),
                plan.candidateSourceSha256Hex(),
                plan.planDigest()
        );

        List<RenameDiagnostic> diagnostics = planner.verify(tampered, base(baseGraph), candidate(candidateGraph));

        assertTrue(codes(diagnostics).contains("SIR-RENAME-STALE-003"), codes(diagnostics).toString());
        // Dropping the establishments also makes the plan incomplete, and each dropped path is named.
        assertEquals(
                List.of(RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SERVICE_PATH_SURVIVING),
                diagnostics.stream()
                        .filter(diagnostic -> diagnostic.code().equals("SIR-RENAME-PATH-004"))
                        .map(diagnostic -> diagnostic.relativePath().orElseThrow())
                        .sorted()
                        .toList()
        );
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static SymbolId declaredSymbol() {
        return RenameTestFixtures.capability(RenameTestFixtures.model(RenameTestFixtures.source(BASE_NAME))).id();
    }

    private static ProjectGraph baseGraph(SymbolId symbol) {
        return RenameTestFixtures.graph(symbol, BASE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_BASE, RenameTestFixtures.SHA_A)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_BASE, RenameTestFixtures.SHA_B)
                .build();
    }

    private static ProjectGraph movedGraph(SymbolId symbol) {
        return RenameTestFixtures.graph(symbol, CANDIDATE_NAME)
                .service(SERVICE_ARTIFACT, SERVICE_QUALIFIED, RenameTestFixtures.SERVICE_PATH_SURVIVING, RenameTestFixtures.SHA_C)
                .controller(CONTROLLER_ARTIFACT, CONTROLLER_QUALIFIED, RenameTestFixtures.CONTROLLER_PATH_CANDIDATE, RenameTestFixtures.SHA_D)
                .build();
    }

    private static RenamePlanRequest requestFor(ProjectGraph baseGraph, SymbolId symbol) {
        return new RenamePlanRequest(revision(baseGraph), symbol);
    }

    private static ChangeBaseRevision revision(ProjectGraph baseGraph) {
        return new ChangeBaseRevision(
                RenameTestFixtures.SOURCE_ID,
                declaredBaseDigest(),
                GraphVersion.V0_1,
                baseGraph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1
        );
    }

    /** The declared source text of the base fixture; a revision binds both its graph and its bytes. */
    private static final String BASE_SOURCE = RenameTestFixtures.source(BASE_NAME);
    private static final String CANDIDATE_SOURCE = RenameTestFixtures.source(CANDIDATE_NAME);

    private static String declaredBaseDigest() {
        return sourceDigest(BASE_SOURCE);
    }

    private static String sourceDigest(String sourceText) {
        return RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, sourceText).sha256Hex();
    }

    private static RenameRevisionSnapshot base(ProjectGraph graph) {
        return new RenameRevisionSnapshot(graph, RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, BASE_SOURCE));
    }

    private static RenameRevisionSnapshot candidate(ProjectGraph graph) {
        return new RenameRevisionSnapshot(graph, RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, CANDIDATE_SOURCE));
    }

    private static RenamePlanningInput input(ProjectGraph baseGraph, ProjectGraph candidateGraph, SymbolId symbol) {
        return new RenamePlanningInput(
                RenameTestFixtures.model(BASE_SOURCE),
                RenameTestFixtures.model(CANDIDATE_SOURCE),
                base(baseGraph),
                candidate(candidateGraph),
                requestFor(baseGraph, symbol)
        );
    }

    private static RenamePlan plan(RenameAnalysis analysis) {
        assertInstanceOf(RenameAnalysis.Planned.class, analysis, () -> "expected a plan: " + codes(analysis));
        return ((RenameAnalysis.Planned) analysis).plan();
    }

    private static List<String> codes(RenameAnalysis analysis) {
        return analysis.diagnostics().stream().map(RenameDiagnostic::code).toList();
    }

    private static List<String> codes(List<RenameDiagnostic> diagnostics) {
        return diagnostics.stream().map(RenameDiagnostic::code).toList();
    }

    @Test
    void aPlanNamesBothNamesAndNotOneOfThemTwice() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RenameSubject(declaredSymbol(), RenameSubjectKind.CAPABILITY, BASE_NAME, BASE_NAME));
        assertNotEquals(BASE_NAME, CANDIDATE_NAME);
    }
}
