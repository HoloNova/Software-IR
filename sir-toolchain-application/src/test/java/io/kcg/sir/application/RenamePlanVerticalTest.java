package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ChangeApplyRequest;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.change.api.ArtifactAddition;
import io.kcg.sir.change.api.ArtifactChange;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.ImpactedArtifact;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.RenameAnalysis;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenamePlanRequest;
import io.kcg.sir.change.api.RenamePlanningInput;
import io.kcg.sir.change.api.RenamePlanner;
import io.kcg.sir.change.api.RenameRevisionSnapshot;
import io.kcg.sir.change.api.RenameSourceSnapshot;
import io.kcg.sir.change.api.RenameSubjectKind;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rename plan over a real single-file SIR (Q16 review gap 1).
 *
 * <p>Everything before this test planned renames over hand-built graphs. Here the two revisions come
 * out of the real pipeline — parse, semantic analysis, Spring Boot lowering, generation, Project Graph
 * construction — from real source files, and the plan is asked about the managed files that pipeline
 * actually emitted. The candidate is not written by hand: it is the locked baseline with the
 * capability renamed, which is the flow the work order describes.
 *
 * <p>The test also states what the unit does <em>not</em> do. Both revisions are generated into their
 * own roots, the plan is computed against them, and both roots are byte-for-byte unchanged afterwards:
 * no apply, no cleanup, no CURRENT.
 */
class RenamePlanVerticalTest {
    private static final String RESOURCE = "valid/rename-course-search.sir";
    /** The work order's own fixture: the one whose global replace renames an input declaration too. */
    private static final String COURSE_RESOURCE = "valid/course-admin-enrollment.sir";
    private static final SourceId SOURCE_ID = SourceId.of("rename-course-search.sir");
    private static final String BASE_NAME = "SearchCourseEnrollments";
    private static final String CANDIDATE_NAME = "SearchEnrollments";
    private static final String DECLARED_CAPABILITY_ID = "search-course-enrollments";
    private static final String DECLARED_FIELD_ID = "course-code";
    private static final String BYSTANDER_NAME = "CountCourses";
    private static final String BYSTANDER_CANDIDATE_NAME = "CountCourseTotals";
    private static final String BASE_SOURCE = resource();
    /** Equal-length comment swap: every token keeps its offset, only the source bytes change. */
    private static final String COMMENTED_BASE_SOURCE =
            BASE_SOURCE.replace("/* capability */", "/* identities */");

    /** The original course fixture text plus one {@code @id}: the same edit the work order measures. */
    private static final String COURSE_BASE_SOURCE = courseSourceWithDeclaredId();
    private static final String COURSE_CANDIDATE_SOURCE = COURSE_BASE_SOURCE.replace(BASE_NAME, CANDIDATE_NAME);
    private static final String COURSE_PACKAGE = "com/example/courseadmin";
    private static final String COURSE_INPUT_PATH_BASE =
            "src/main/java/" + COURSE_PACKAGE + "/api/" + BASE_NAME + "Input.java";
    private static final String COURSE_INPUT_PATH_CANDIDATE =
            "src/main/java/" + COURSE_PACKAGE + "/api/" + CANDIDATE_NAME + "Input.java";

    /** One real run per distinct source text keeps this class to three pipeline executions. */
    private static final Map<String, Revision> REVISIONS = new HashMap<>();

    @TempDir
    static Path temporaryDirectory;

    private final RenamePlanner planner = new RenamePlanner();

    @Test
    void aRealSingleFileRenameIsPlannedOverTheFilesTheTargetActuallyEmits() throws Exception {
        Revision base = base();
        Revision candidate = candidate();
        SymbolId subject = declaredCapabilitySymbol(base.model());
        RenamePlan plan = plan(base, candidate, subject);

        assertEquals(RenameSubjectKind.CAPABILITY, plan.subject().kind());
        assertEquals(subject, plan.subject().declarationSymbol());
        assertEquals(BASE_NAME, plan.subject().baseName());
        assertEquals(CANDIDATE_NAME, plan.subject().candidateName());

        Set<String> baseOwned = filesOwnedBy(base, subject);
        Set<String> candidateOwned = filesOwnedBy(candidate, subject);
        assertFalse(baseOwned.isEmpty(), "the real pipeline must emit managed files for the subject");
        assertEquals(
                List.of(pathOf(base, subject, "SearchCourseEnrollmentsController.java"),
                        pathOf(base, subject, "SearchCourseEnrollmentsService.java")),
                plan.withdrawals().stream().map(withdrawal -> withdrawal.relativePath()).toList());
        assertEquals(
                List.of(pathOf(candidate, subject, "SearchEnrollmentsController.java"),
                        pathOf(candidate, subject, "SearchEnrollmentsService.java")),
                plan.establishments().stream().map(establishment -> establishment.relativePath()).toList());
        assertEquals(
                baseOwned,
                new LinkedHashSet<>(plan.withdrawals().stream().map(value -> value.relativePath()).toList()),
                "every managed file this declaration owns must be withdrawn");
        assertEquals(
                candidateOwned,
                new LinkedHashSet<>(plan.establishments().stream().map(value -> value.relativePath()).toList()),
                "every managed file this declaration owns in the candidate must be established");
        assertEquals(
                List.of(),
                plan.updates(),
                "with this target both generated files move, so no file of the closure survives in place");
    }

    @Test
    void thePlannedDigestsAreTheBytesOnDisk() throws Exception {
        Revision base = base();
        Revision candidate = candidate();
        RenamePlan plan = plan(base, candidate, declaredCapabilitySymbol(base.model()));

        for (var withdrawal : plan.withdrawals()) {
            assertEquals(
                    digestOfFile(base.root().resolve(withdrawal.relativePath())),
                    withdrawal.sha256Hex(),
                    "planned withdrawal digest must be the file on disk: " + withdrawal.relativePath());
            assertTrue(base.manifestPaths().contains(withdrawal.relativePath()));
        }

        for (var establishment : plan.establishments()) {
            assertEquals(
                    digestOfFile(candidate.root().resolve(establishment.relativePath())),
                    establishment.sha256Hex(),
                    "planned establishment digest must be the file on disk: " + establishment.relativePath());
        }
    }

    @Test
    void onlyTheRenamedDeclarationFilesDifferBetweenTheTwoRevisions() throws Exception {
        Revision base = base();
        Revision candidate = candidate();
        RenamePlan plan = plan(base, candidate, declaredCapabilitySymbol(base.model()));

        Set<String> touched = new LinkedHashSet<>();
        plan.updates().forEach(value -> touched.add(value.relativePath()));
        plan.withdrawals().forEach(value -> touched.add(value.relativePath()));
        plan.establishments().forEach(value -> touched.add(value.relativePath()));

        Set<String> differing = new LinkedHashSet<>();
        for (String path : base.manifestPaths()) {
            String candidateDigest = candidate.manifestDigests().get(path);
            if (candidateDigest == null || !candidateDigest.equals(base.manifestDigests().get(path))) {
                differing.add(path);
            }
        }

        for (String path : candidate.manifestPaths()) {
            if (!base.manifestDigests().containsKey(path)) {
                differing.add(path);
            }
        }

        assertEquals(touched, differing, "the plan must name exactly the paths whose content changed");
        assertTrue(touched.stream().noneMatch(path -> path.contains("CountCourses")),
                "a capability without a declared id must not appear in another declaration's plan");
        assertTrue(touched.stream().noneMatch(path -> path.contains("GetCourseInput")),
                "files the rename does not change must stay out of the plan: " + touched);
    }

    @Test
    void planningWritesNothingAndTheOldAndNewPathsCoexist() throws Exception {
        Revision base = base();
        Revision candidate = candidate();
        Map<String, String> baseBefore = inventory(base.root());
        Map<String, String> candidateBefore = inventory(candidate.root());

        RenamePlan first = plan(base, candidate, declaredCapabilitySymbol(base.model()));
        RenamePlan second = plan(base, candidate, declaredCapabilitySymbol(base.model()));

        assertEquals(baseBefore, inventory(base.root()), "planning must not change the baseline root");
        assertEquals(candidateBefore, inventory(candidate.root()), "planning must not change the candidate root");
        assertEquals(first, second, "planning the same revisions twice must produce the same plan");
        assertEquals(first.planDigest(), second.planDigest());
        assertEquals(
                List.of(),
                planner.verify(first, base.snapshot(), candidate.snapshot()),
                "a plan over unchanged revisions must verify clean");

        // The plan is a statement: after it exists, the baseline root still holds only the old paths
        // and the candidate root still holds only the new ones. Nothing withdrew or established.
        assertTrue(baseBefore.keySet().containsAll(
                first.withdrawals().stream().map(value -> value.relativePath()).toList()));
        assertTrue(first.establishments().stream()
                .noneMatch(value -> baseBefore.containsKey(value.relativePath())));
        assertTrue(candidateBefore.keySet().containsAll(
                first.establishments().stream().map(value -> value.relativePath()).toList()));
        assertTrue(first.withdrawals().stream()
                .noneMatch(value -> candidateBefore.containsKey(value.relativePath())));
    }

    @Test
    void theDeclaredIdentitiesInTheRealSourceSurviveTheRename() throws Exception {
        Revision base = base();
        Revision candidate = candidate();

        SymbolId capability = declaredCapabilitySymbol(base.model());
        assertEquals(capability, declaredCapabilitySymbol(candidate.model()));
        assertEquals("sir://RenameCourseSearch/declared/capability/" + DECLARED_CAPABILITY_ID, capability.value());

        SymbolId declaredField = entityField(base.model(), "code").id();
        assertEquals(
                "sir://RenameCourseSearch/declared/entity-field/" + DECLARED_FIELD_ID,
                declaredField.value());
        assertEquals(declaredField, entityField(candidate.model(), "code").id());

        // A member without @id keeps the name-derived identity it always had, in both revisions.
        NormalizedField legacy = entityField(base.model(), "title");
        assertTrue(
                legacy.id().value().startsWith("sir://RenameCourseSearch/entity/Course/field/"),
                legacy.id().value());
        assertEquals(legacy.id(), entityField(candidate.model(), "title").id());

        // The same holds for a capability without @id: it is not a rename subject, and its name-derived
        // identity must not be reinterpreted as a declared one.
        SymbolId bystander = capabilitySymbol(base.model(), "CountCourses");
        assertEquals(new SymbolId("sir://RenameCourseSearch/capability/CountCourses"), bystander);
        assertEquals(bystander, capabilitySymbol(candidate.model(), "CountCourses"));
    }

    @Test
    void aCommentOnlyEditKeepsTheGraphButStillInvalidatesThePlan() throws Exception {
        Revision base = base();
        Revision candidate = candidate();
        RenamePlan plan = plan(base, candidate, declaredCapabilitySymbol(base.model()));

        Revision commented = commented();
        assertEquals(
                base.graph().canonicalDigest(),
                commented.graph().canonicalDigest(),
                "an equal-length comment edit moves no token, so the projected graph must be identical");
        assertNotEquals(
                base.source().sha256Hex(),
                commented.source().sha256Hex(),
                "the same edit must change the source bytes");

        assertEquals(
                List.of("SIR-RENAME-STALE-004"),
                codes(planner.verify(plan, commented.snapshot(), candidate.snapshot())),
                "the unchanged graph must not hide the changed source");
    }

    @Test
    void theSixChangeFamiliesAndTheirOneFamilyRuleAreUntouched() throws Exception {
        Revision base = base();
        SymbolId subject = declaredCapabilitySymbol(base.model());
        NormalizedCapability capability = capability(base.model(), BASE_NAME);

        assertEquals(6, ChangeOperation.class.getPermittedSubclasses().length);
        assertFalse(ChangePlan.class.isAssignableFrom(RenamePlan.class));

        ChangeSet changeSet = new ChangeSet(
                ChangeIrVersion.V0_1,
                new ChangeBaseRevision(
                        SOURCE_ID,
                        base.source().sha256Hex(),
                        GraphVersion.V0_1,
                        base.graph().canonicalDigest(),
                        ProjectGraphCanonicalFormatVersion.V1),
                List.of(new ModifyCapabilityWorkflow(
                        new ChangeTarget(subject, capability.sourceNodeId(), capability.workflow().sourceNodeId()))));
        FileChange fileChange = new FileChange(
                pathOf(base, subject, "SearchCourseEnrollmentsService.java"),
                new LoweredNodeId("lir://spring/artifact-service/anything"),
                Optional.of(subject),
                1L,
                digestOfText("a"),
                2L,
                digestOfText("b"));
        ArtifactChange artifactChange = new ArtifactChange(
                new ImpactedArtifact(
                        fileChange.artifactId(),
                        fileChange.ownerSymbol(),
                        ArtifactRole.DeclarationRole.SERVICE,
                        "com.example.rename.application.SearchCourseEnrollmentsService",
                        List.of(fileChange)),
                List.of(fileChange));
        FileAddition fileAddition = new FileAddition(
                "src/main/java/com/example/rename/application/SearchEnrollmentsService.java",
                fileChange.artifactId(),
                subject,
                1L,
                digestOfText("c"));
        ArtifactAddition artifactAddition = new ArtifactAddition(
                fileChange.artifactId(),
                subject,
                ArtifactRole.DeclarationRole.SERVICE,
                "com.example.rename.application.SearchEnrollmentsService",
                List.of(fileAddition));

        IllegalArgumentException mixed = assertThrows(
                IllegalArgumentException.class,
                () -> new ChangePlan(
                        changeSet,
                        List.of(artifactChange),
                        List.of(fileChange),
                        List.of(artifactAddition),
                        List.of(fileAddition),
                        List.of(),
                        List.of()));
        assertTrue(mixed.getMessage().contains("must not mix"), mixed.getMessage());

        // A rename plan is not accepted by any input of the apply path, so it cannot be applied by
        // mistake: the apply request binds a ChangeSet and a candidate SIR, never a RenamePlan.
        assertFalse(ChangePlan.class.isAssignableFrom(RenamePlan.class));
        assertFalse(Arrays.stream(ChangeApplyRequest.class.getRecordComponents())
                .anyMatch(component -> component.getType() == RenamePlan.class));
        for (Method method : ChangeExecutionApplication.class.getMethods()) {
            assertFalse(
                    Arrays.stream(method.getParameterTypes()).anyMatch(RenamePlan.class::equals),
                    "no apply entry point may accept a rename plan: " + method);
        }
    }

    @Test
    void theOriginalCourseScenarioIsRefusedBecauseItsInputDtoChangesToo() throws Exception {
        // The work-order scenario, on the real fixture: the reviewer's literal global replace of
        // SearchCourseEnrollments hits exactly three occurrences - the input declaration, the
        // capability name and the capability's input reference - so the capability's own DTO moves
        // as well. The declaration's closure owns only the service and the controller, so a plan
        // built from the closure cannot cover the DTO; the planner has to refuse instead of
        // returning a plan that looks complete.
        Revision base = courseBase();
        Revision candidate = courseCandidate();
        SymbolId subject = declaredCapabilitySymbol(base.model());

        Set<String> changed = changedPaths(base, candidate);
        assertEquals(
                6,
                changed.size(),
                "three files moved, so three old paths went and three new ones arrived: " + changed);
        assertTrue(changed.contains(COURSE_INPUT_PATH_BASE), changed.toString());
        assertTrue(changed.contains(COURSE_INPUT_PATH_CANDIDATE), changed.toString());

        // What the plan used to be built from: the renamed declaration's own managed files only.
        Set<String> closureFiles = filesOwnedBy(base, subject);
        assertEquals(2, closureFiles.size(), "the closure owns the service and the controller: " + closureFiles);
        assertTrue(
                closureFiles.stream().noneMatch(path -> path.endsWith("Input.java")),
                "the DTO is not part of the declaration's closure: " + closureFiles);

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                base.model(),
                candidate.model(),
                base.snapshot(),
                candidate.snapshot(),
                new RenamePlanRequest(
                        new ChangeBaseRevision(
                                SOURCE_ID,
                                base.source().sha256Hex(),
                                GraphVersion.V0_1,
                                base.graph().canonicalDigest(),
                                ProjectGraphCanonicalFormatVersion.V1),
                        subject)));

        assertInstanceOf(RenameAnalysis.Rejected.class, analysis, () -> "expected a rejection: " + analysis);
        assertEquals(List.of("SIR-RENAME-PATH-004", "SIR-RENAME-PATH-004"), analysis.diagnostics().stream()
                .map(io.kcg.sir.change.api.RenameDiagnostic::code)
                .toList());
        assertEquals(
                List.of(COURSE_INPUT_PATH_BASE, COURSE_INPUT_PATH_CANDIDATE),
                analysis.diagnostics().stream()
                        .map(diagnostic -> diagnostic.relativePath().orElseThrow())
                        .sorted()
                        .toList(),
                "the refusal must name the DTO paths the plan cannot cover");
    }

    @Test
    void anotherRenamedCapabilityInTheSameProjectIsRefused() throws Exception {
        // A second capability renamed in the same edit is a managed-file change this plan cannot
        // explain, so the planner refuses rather than planning only the declaration it was asked about.
        Revision base = base();
        Revision candidate = candidateWithBystanderRenamed();

        Set<String> changed = changedPaths(base, candidate);
        assertTrue(changed.stream().anyMatch(path -> path.contains("CountCourses")), changed.toString());
        assertTrue(changed.stream().anyMatch(path -> path.contains("CountCourseTotals")), changed.toString());

        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                base.model(),
                candidate.model(),
                base.snapshot(),
                candidate.snapshot(),
                new RenamePlanRequest(
                        new ChangeBaseRevision(
                                SOURCE_ID,
                                base.source().sha256Hex(),
                                GraphVersion.V0_1,
                                base.graph().canonicalDigest(),
                                ProjectGraphCanonicalFormatVersion.V1),
                        declaredCapabilitySymbol(base.model()))));

        assertInstanceOf(RenameAnalysis.Rejected.class, analysis, () -> "expected a rejection: " + analysis);
        assertEquals(
                4,
                analysis.diagnostics().size(),
                "both of the bystander's files moved: " + analysis.diagnostics());
        assertTrue(analysis.diagnostics().stream()
                .allMatch(diagnostic -> diagnostic.code().equals("SIR-RENAME-PATH-004")));
        assertTrue(analysis.diagnostics().stream()
                .allMatch(diagnostic -> diagnostic.relativePath().orElseThrow().contains("CountCourse")));
    }

    // ---- the real pipeline ---------------------------------------------------------------------

    private static Revision base() throws IOException {
        return revision("baseline", BASE_SOURCE);
    }

    /** The locked baseline with the capability renamed and its declared id kept. */
    private static String candidateSource() {
        String candidate = BASE_SOURCE.replace(BASE_NAME, CANDIDATE_NAME);
        assertNotEquals(BASE_SOURCE, candidate);
        assertTrue(candidate.contains("@id(\"" + DECLARED_CAPABILITY_ID + "\")"), candidate);
        return candidate;
    }

    private static Revision candidate() throws IOException {
        return revision("candidate", candidateSource());
    }

    private static Revision commented() throws IOException {
        assertNotEquals(BASE_SOURCE, COMMENTED_BASE_SOURCE);
        assertEquals(BASE_SOURCE.length(), COMMENTED_BASE_SOURCE.length(), "the comment swap must keep every offset");
        return revision("commented", COMMENTED_BASE_SOURCE);
    }

    private static String resource() {
        try {
            return ApplicationTestSupport.resource(RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The original {@code course-admin-enrollment.sir} text with exactly one insertion: the declared id
     * on the capability. Everything else - the three occurrences of the capability name, the DTO named
     * after it and the capability's input reference - is the original file's own text, so the global
     * replace below is the work-order scenario rather than a lookalike fixture.
     */
    private static String courseSourceWithDeclaredId() {
        String original = courseResource();
        assertEquals(
                3,
                original.split(java.util.regex.Pattern.quote(BASE_NAME), -1).length - 1,
                "the work order's scenario is a three-occurrence replace");
        String withId = original.replace(
                "capability " + BASE_NAME + " {",
                "capability " + BASE_NAME + " @id(\"" + DECLARED_CAPABILITY_ID + "\") {");
        assertNotEquals(original, withId);
        assertEquals(
                1,
                withId.split(java.util.regex.Pattern.quote(" @id(\""), -1).length - 1,
                "the fixture may differ from the original by the declared id only");
        return withId;
    }

    private static String courseResource() {
        try {
            return ApplicationTestSupport.resource(COURSE_RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Revision courseBase() throws IOException {
        return revision("course-baseline", COURSE_BASE_SOURCE);
    }

    private static Revision courseCandidate() throws IOException {
        assertNotEquals(COURSE_BASE_SOURCE, COURSE_CANDIDATE_SOURCE);
        return revision("course-candidate", COURSE_CANDIDATE_SOURCE);
    }

    /** The minimal fixture's candidate with a second, unrelated capability renamed in the same edit. */
    private static Revision candidateWithBystanderRenamed() throws IOException {
        String source = candidateSource().replace(BYSTANDER_NAME, BYSTANDER_CANDIDATE_NAME);
        assertNotEquals(candidateSource(), source);
        return revision("candidate-with-bystander", source);
    }

    /** Every managed file whose path or content differs between the two revisions. */
    private static Set<String> changedPaths(Revision base, Revision candidate) {
        Map<String, String> baseDigests = managedDigests(base);
        Map<String, String> candidateDigests = managedDigests(candidate);
        Set<String> changed = new LinkedHashSet<>();
        for (String path : baseDigests.keySet()) {
            if (!candidateDigests.containsKey(path) || !candidateDigests.get(path).equals(baseDigests.get(path))) {
                changed.add(path);
            }
        }

        for (String path : candidateDigests.keySet()) {
            if (!baseDigests.containsKey(path)) {
                changed.add(path);
            }
        }

        return changed;
    }

    /** The managed files of one revision as the graph reports them: path to content digest. */
    private static Map<String, String> managedDigests(Revision revision) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (ProjectGraphNode node : revision.graph().nodes()) {
            if (node instanceof ProjectGraphNode.ProjectFile file) {
                digests.put(file.id().relativePath(), file.provenance().sha256Hex());
            }
        }

        return digests;
    }

    private static Revision revision(String name, String sourceText) throws IOException {
        synchronized (REVISIONS) {
            Revision cached = REVISIONS.get(name);
            if (cached != null) {
                return cached;
            }

            Path source = temporaryDirectory.resolve(name + ".sir").toAbsolutePath();
            Files.writeString(source, sourceText, StandardCharsets.UTF_8);
            Path root = temporaryDirectory.resolve(name + "-out").toAbsolutePath();
            ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                    source, SOURCE_ID, root, io.kcg.sir.application.api.ConflictPolicy.FAIL_IF_EXISTS));
            if (!(result instanceof ToolchainResult.Success success)) {
                ToolchainResult.Failure failure = (ToolchainResult.Failure) result;
                throw new AssertionError("expected Success but got Failure at " + failure.failedStage()
                        + ": " + failure.diagnostics());
            }

            Revision revision = new Revision(
                    root,
                    success.graph(),
                    modelOf(sourceText),
                    RenameSourceSnapshot.of(SOURCE_ID, sourceText),
                    success.manifest().files());
            REVISIONS.put(name, revision);
            return revision;
        }
    }

    private static NormalizedSemanticModel modelOf(String sourceText) {
        ParseResult parsed = SirParser.create().parse(new SirSource(SOURCE_ID, sourceText));
        assertTrue(parsed.isSuccess(), () -> "fixture must parse: " + parsed.diagnostics());
        SemanticAnalysis analysis = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        assertTrue(analysis.isSuccess(), () -> "fixture must analyze: " + analysis.diagnostics());
        return analysis.model().orElseThrow();
    }

    private RenamePlan plan(Revision base, Revision candidate, SymbolId subject) {
        RenameAnalysis analysis = planner.plan(new RenamePlanningInput(
                base.model(),
                candidate.model(),
                base.snapshot(),
                candidate.snapshot(),
                new RenamePlanRequest(
                        new ChangeBaseRevision(
                                SOURCE_ID,
                                base.source().sha256Hex(),
                                GraphVersion.V0_1,
                                base.graph().canonicalDigest(),
                                ProjectGraphCanonicalFormatVersion.V1),
                        subject)));

        assertInstanceOf(RenameAnalysis.Planned.class, analysis, () -> "expected a plan: " + analysis.diagnostics());
        return ((RenameAnalysis.Planned) analysis).plan();
    }

    // ---- reading the real revisions ------------------------------------------------------------

    /** One revision: where it was generated, its graph, its model and its source bytes. */
    private record Revision(
            Path root,
            ProjectGraph graph,
            NormalizedSemanticModel model,
            RenameSourceSnapshot source,
            List<AppliedFile> files) {
        RenameRevisionSnapshot snapshot() {
            return new RenameRevisionSnapshot(graph, source);
        }

        Set<String> manifestPaths() {
            return files.stream().map(AppliedFile::relativePath).collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Map<String, String> manifestDigests() {
            Map<String, String> digests = new LinkedHashMap<>();
            for (AppliedFile file : files) {
                digests.put(file.relativePath(), file.sha256Hex());
            }

            return digests;
        }
    }

    /** The managed files the graph attributes to one declaration. */
    private static Set<String> filesOwnedBy(Revision revision, SymbolId symbol) {
        Set<String> paths = new LinkedHashSet<>();
        for (ProjectGraphNode node : revision.graph().nodes()) {
            if (node instanceof ProjectGraphNode.ProjectFile file
                    && file.provenance().ownerSymbol().filter(symbol::equals).isPresent()) {
                paths.add(file.id().relativePath());
            }
        }

        return paths;
    }

    private static String pathOf(Revision revision, SymbolId subject, String fileName) {
        Set<String> owned = filesOwnedBy(revision, subject);
        return owned.stream()
                .filter(path -> path.endsWith("/" + fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no managed file named " + fileName + " among " + owned));
    }

    /** The declared identity of the rename subject, which is the same symbol in both revisions. */
    private static SymbolId declaredCapabilitySymbol(NormalizedSemanticModel model) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability
                    && capability.id().value().endsWith("/declared/capability/" + DECLARED_CAPABILITY_ID)) {
                return capability.id();
            }
        }

        throw new AssertionError("no capability with declared id " + DECLARED_CAPABILITY_ID + " in the model");
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model, String name) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability && capability.name().equals(name)) {
                return capability;
            }
        }

        throw new AssertionError("no " + name + " capability in the model");
    }

    private static SymbolId capabilitySymbol(NormalizedSemanticModel model, String name) {
        return capability(model, name).id();
    }

    private static NormalizedField entityField(NormalizedSemanticModel model, String name) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedEntity entity) {
                for (NormalizedField field : entity.fields()) {
                    if (field.name().equals(name)) {
                        return field;
                    }
                }
            }
        }

        throw new AssertionError("no entity member named " + name + " in the model");
    }

    private static Map<String, String> inventory(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(paths::add);
        }

        paths.sort(Path::compareTo);
        for (Path path : paths) {
            files.put(root.relativize(path).toString().replace('\\', '/'), digestOfFile(path));
        }

        return files;
    }

    private static String digestOfFile(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }

            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String digestOfText(String text) {
        return RenameSourceSnapshot.of(SOURCE_ID, text).sha256Hex();
    }

    private static List<String> codes(List<io.kcg.sir.change.api.RenameDiagnostic> diagnostics) {
        return diagnostics.stream().map(io.kcg.sir.change.api.RenameDiagnostic::code).toList();
    }
}
