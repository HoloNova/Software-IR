package io.kcg.sir.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.RenameAnalysis;
import io.kcg.sir.change.api.RenameDiagnostic;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The plan may not be narrower than the change (Q16 acceptance review, unsafe-plan gap).
 *
 * <p>The three file sets used to come only from the renamed declaration's closure, so a project change
 * outside that closure — an input DTO named after the capability, another declaration, a project file
 * the target regenerates — was silently absent from the plan while still being a managed-file change.
 * These tests pin the rule that closed it: the plan's sets must be exactly the managed-file diff of the
 * two revisions, and a plan (or a forged copy of one) that misses or invents a path is refused with
 * that path named.
 */
class RenamePlanCoverageTest {

    private static final String BASE_NAME = "SearchCourseEnrollments";
    private static final String CANDIDATE_NAME = "SearchEnrollments";
    private static final String BASE_SOURCE = RenameTestFixtures.source(BASE_NAME);
    private static final String CANDIDATE_SOURCE = RenameTestFixtures.source(CANDIDATE_NAME);
    private static final LoweredNodeId SERVICE_ARTIFACT = new LoweredNodeId("lir://spring/service/declared");
    private static final LoweredNodeId CONTROLLER_ARTIFACT = new LoweredNodeId("lir://spring/controller/declared");
    /** A declaration that is not the rename subject: the file it generates belongs to somebody else. */
    private static final SymbolId FOREIGN_SYMBOL = new SymbolId("sir://Rename/declared/capability/count-courses");
    private static final LoweredNodeId FOREIGN_SERVICE = new LoweredNodeId("lir://spring/service/count-courses");
    private static final String FOREIGN_PATH_BASE = "src/main/java/com/example/rename/application/CountCoursesService.java";
    private static final String FOREIGN_PATH_CANDIDATE_CAPACITY =
            "src/main/java/com/example/rename/application/CountCourseCapacityService.java";

    private final RenamePlanner planner = new RenamePlanner();

    @Test
    void aProjectFileThatChangedOutsideTheClosureIsRejected() {
        // Same declarations and same generated sources; only the project's own pom.xml content moved.
        ProjectGraph baseGraph = baseGraph().pomDigest(RenameTestFixtures.SHA_POM).build();
        ProjectGraph candidateGraph = candidateGraph().pomDigest(RenameTestFixtures.SHA_D).build();

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph));

        assertEquals(List.of("SIR-RENAME-PATH-004"), codes(analysis));
        assertEquals(List.of("pom.xml"), paths(analysis));
    }

    @Test
    void aSecondDeclarationsFileChangeIsRejected() {
        // The candidate also moved another declaration's file, which this rename cannot explain.
        ProjectGraph baseGraph = baseGraph()
                .foreignOwner(FOREIGN_SERVICE, FOREIGN_SYMBOL, "CountCourses", FOREIGN_PATH_BASE, RenameTestFixtures.SHA_A)
                .build();
        ProjectGraph candidateGraph = candidateGraph()
                .foreignOwner(
                        FOREIGN_SERVICE,
                        FOREIGN_SYMBOL,
                        "CountCourses",
                        FOREIGN_PATH_CANDIDATE_CAPACITY,
                        RenameTestFixtures.SHA_C)
                .build();

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph));

        assertEquals(List.of("SIR-RENAME-PATH-004", "SIR-RENAME-PATH-004"), codes(analysis));
        assertEquals(List.of(FOREIGN_PATH_CANDIDATE_CAPACITY, FOREIGN_PATH_BASE), paths(analysis));
    }

    @Test
    void aManagedFileThatAppearsOnlyInTheCandidateIsRejected() {
        ProjectGraph baseGraph = baseGraph().foreignOwner(
                FOREIGN_SERVICE, FOREIGN_SYMBOL, "CountCourses", FOREIGN_PATH_BASE, RenameTestFixtures.SHA_A).build();
        ProjectGraph candidateGraph = candidateGraph().build();

        RenameAnalysis analysis = planner.plan(input(baseGraph, candidateGraph));

        assertEquals(List.of("SIR-RENAME-PATH-004"), codes(analysis));
        assertEquals(List.of(FOREIGN_PATH_BASE), paths(analysis));
    }

    @Test
    void aPlanThatDroppedAnEstablishmentCannotClaimCoverage() {
        ProjectGraph baseGraph = baseGraph().build();
        ProjectGraph candidateGraph = candidateGraph().build();
        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph)));
        assertFalse(plan.establishments().isEmpty(), "the fixture must have an establishment to drop");

        RenameFileEstablishment dropped = plan.establishments().getFirst();
        RenamePlan incomplete = new RenamePlan(
                plan.basedOn(),
                plan.subject(),
                plan.updates(),
                plan.withdrawals(),
                plan.establishments().stream().filter(value -> value != dropped).toList(),
                plan.baseGraphCanonicalDigest(),
                plan.candidateGraphCanonicalDigest(),
                plan.baseSourceSha256Hex(),
                plan.candidateSourceSha256Hex(),
                plan.planDigest());

        List<RenameDiagnostic> diagnostics = planner.verify(incomplete, base(baseGraph), candidate(candidateGraph));

        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("SIR-RENAME-PATH-004")
                        && diagnostic.relativePath().orElse("").equals(dropped.relativePath())),
                diagnostics.toString());
    }

    @Test
    void aPlanThatInventedAnUnchangedUpdateCannotClaimCoverage() {
        ProjectGraph baseGraph = baseGraph().pomDigest(RenameTestFixtures.SHA_POM).build();
        ProjectGraph candidateGraph = candidateGraph().pomDigest(RenameTestFixtures.SHA_POM).build();
        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph)));

        // pom.xml is identical in both revisions, so a plan naming it as an update invents work.
        RenamePlan withInventedUpdate = new RenamePlan(
                plan.basedOn(),
                plan.subject(),
                List.of(new RenameFileUpdate(
                        "pom.xml",
                        new LoweredNodeId("lir://spring/maven/project"),
                        Optional.empty(),
                        10L,
                        RenameTestFixtures.SHA_POM,
                        10L,
                        RenameTestFixtures.SHA_POM)),
                plan.withdrawals(),
                plan.establishments(),
                plan.baseGraphCanonicalDigest(),
                plan.candidateGraphCanonicalDigest(),
                plan.baseSourceSha256Hex(),
                plan.candidateSourceSha256Hex(),
                plan.planDigest());

        List<RenameDiagnostic> diagnostics = planner.verify(withInventedUpdate, base(baseGraph), candidate(candidateGraph));

        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.code().equals("SIR-RENAME-PATH-004")
                        && diagnostic.relativePath().orElse("").equals("pom.xml")),
                diagnostics.toString());
    }

    @Test
    void anUnchangedProjectFileDoesNotHaveToBeNamedByThePlan() {
        // The boundary of the rule: a managed file present in both revisions with the same digest is not
        // a change, so a plan that leaves it alone is correct and verifies clean.
        ProjectGraph baseGraph = baseGraph().pomDigest(RenameTestFixtures.SHA_POM).build();
        ProjectGraph candidateGraph = candidateGraph().pomDigest(RenameTestFixtures.SHA_POM).build();

        RenamePlan plan = plan(planner.plan(input(baseGraph, candidateGraph)));

        assertTrue(plan.updates().stream().noneMatch(update -> update.relativePath().equals("pom.xml")));
        assertTrue(plan.withdrawals().stream().noneMatch(withdrawal -> withdrawal.relativePath().equals("pom.xml")));
        assertTrue(plan.establishments().stream()
                .noneMatch(establishment -> establishment.relativePath().equals("pom.xml")));
        assertEquals(List.of(), planner.verify(plan, base(baseGraph), candidate(candidateGraph)));
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static RenameTestFixtures.GraphFixture baseGraph() {
        return RenameTestFixtures.graph(declaredSymbol(), BASE_NAME)
                .service(
                        SERVICE_ARTIFACT,
                        "com.example.rename.application." + BASE_NAME + "Service",
                        RenameTestFixtures.SERVICE_PATH_BASE,
                        RenameTestFixtures.SHA_A)
                .controller(
                        CONTROLLER_ARTIFACT,
                        "com.example.rename.api." + BASE_NAME + "Controller",
                        RenameTestFixtures.CONTROLLER_PATH_BASE,
                        RenameTestFixtures.SHA_B);
    }

    private static RenameTestFixtures.GraphFixture candidateGraph() {
        return RenameTestFixtures.graph(declaredSymbol(), CANDIDATE_NAME)
                .service(
                        SERVICE_ARTIFACT,
                        "com.example.rename.application." + CANDIDATE_NAME + "Service",
                        RenameTestFixtures.SERVICE_PATH_SURVIVING,
                        RenameTestFixtures.SHA_C)
                .controller(
                        CONTROLLER_ARTIFACT,
                        "com.example.rename.api." + CANDIDATE_NAME + "Controller",
                        RenameTestFixtures.CONTROLLER_PATH_CANDIDATE,
                        RenameTestFixtures.SHA_D);
    }

    private static SymbolId declaredSymbol() {
        return RenameTestFixtures.capability(RenameTestFixtures.model(RenameTestFixtures.source(BASE_NAME))).id();
    }

    private RenamePlanningInput input(ProjectGraph baseGraph, ProjectGraph candidateGraph) {
        return new RenamePlanningInput(
                RenameTestFixtures.model(BASE_SOURCE),
                RenameTestFixtures.model(CANDIDATE_SOURCE),
                base(baseGraph),
                candidate(candidateGraph),
                new RenamePlanRequest(revision(baseGraph), declaredSymbol()));
    }

    private static ChangeBaseRevision revision(ProjectGraph baseGraph) {
        return new ChangeBaseRevision(
                RenameTestFixtures.SOURCE_ID,
                RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, BASE_SOURCE).sha256Hex(),
                GraphVersion.V0_1,
                baseGraph.canonicalDigest(),
                ProjectGraphCanonicalFormatVersion.V1);
    }

    private static RenameRevisionSnapshot base(ProjectGraph graph) {
        return new RenameRevisionSnapshot(graph, RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, BASE_SOURCE));
    }

    private static RenameRevisionSnapshot candidate(ProjectGraph graph) {
        return new RenameRevisionSnapshot(graph, RenameSourceSnapshot.of(RenameTestFixtures.SOURCE_ID, CANDIDATE_SOURCE));
    }

    private static RenamePlan plan(RenameAnalysis analysis) {
        assertInstanceOf(RenameAnalysis.Planned.class, analysis, () -> "expected a plan: " + codes(analysis));
        return ((RenameAnalysis.Planned) analysis).plan();
    }

    private static List<String> codes(RenameAnalysis analysis) {
        return analysis.diagnostics().stream().map(RenameDiagnostic::code).toList();
    }

    private static List<String> paths(RenameAnalysis analysis) {
        return analysis.diagnostics().stream()
                .map(diagnostic -> diagnostic.relativePath().orElseThrow())
                .sorted()
                .toList();
    }
}
