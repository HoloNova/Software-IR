package io.kcg.sir.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.RenameAnalysis;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.change.api.RenamePlanRequest;
import io.kcg.sir.change.api.RenamePlanningInput;
import io.kcg.sir.change.api.RenamePlanner;
import io.kcg.sir.change.api.RenameRevisionSnapshot;
import io.kcg.sir.change.api.RenameSourceSnapshot;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Q17 pre-flight measurements (work order {@code docs/roadmap/ACTIVE_WORK.md} §4, P1–P4).
 *
 * <p>These probe the premises the Q17 transaction design rests on, before any production change:
 *
 * <ul>
 *   <li>P1 — can a legal, real-pipeline rename plan ever carry a non-empty {@code updates} set?
 *   <li>P2 — is every entry of a real plan's three sets carrying a non-empty {@code ownerSymbol}
 *       (the V2/V3 payload constructors require it)?
 *   <li>P3 — is "same-volume hard link backup + {@code ATOMIC_MOVE}/{@code REPLACE_EXISTING}" a
 *       reliable update leg, and which post-link checks detect an outside writer?
 *   <li>P4 — do the real plan's paths conflict (equal or nested) in a way that forces an operation
 *       order?
 * </ul>
 *
 * <p>Nothing here mirrors the Q17 transaction: P3 exercises {@code java.nio.file} primitives only,
 * P1/P2/P4 assert properties of plans the Q16 planner already produces. The measurements are the
 * evidence recorded in {@code ACTIVE_WORK.md}'s pre-flight section; the Q17 transaction is not
 * implemented and must not be inferred from these tests.
 */
class Q17PreflightProbeTest {

    private static final SourceId COURSE_SOURCE_ID = SourceId.of("rename-course-search.sir");
    private static final String COURSE_BASE = courseSearchSource();
    private static final String COURSE_CANDIDATE = COURSE_BASE.replace("SearchCourseEnrollments", "SearchEnrollments");
    private static final String DECLARED_CAPABILITY_ID = "search-course-enrollments";

    private static final SourceId MARKET_SOURCE_ID = SourceId.of("campus-market.sir");
    /** The actor-bearing fixture: its capability rename also changes a path-stable project file. */
    private static final String MARKET_BASE = campusMarketWithDeclaredId();
    private static final String MARKET_CANDIDATE = MARKET_BASE.replace(
            "capability PublishGoods", "capability PublishItems");

    @TempDir
    Path tempDir;

    private final RenamePlanner planner = new RenamePlanner();

    // ---- P1 ------------------------------------------------------------------------------------

    @Test
    void p1RealCapabilityRenameHasNoUpdatesBecauseBothClosureFilesMove() throws Exception {
        Revision base = revision("p1-course-base", COURSE_SOURCE_ID, COURSE_BASE);
        Revision candidate = revision("p1-course-candidate", COURSE_SOURCE_ID, COURSE_CANDIDATE);
        RenamePlan plan = plan(base, candidate, declaredCapability(base.model()));

        assertEquals(
                List.of(),
                plan.updates(),
                "a capability rename moves both of its own files, so the closure holds no surviving path: "
                        + plan.updates());
        assertFalse(plan.withdrawals().isEmpty(), "the old name's files are withdrawn");
        assertFalse(plan.establishments().isEmpty(), "the new name's files are established");
        // The closure only ever holds the capability's own SERVICE and CONTROLLER files, and both
        // paths are derived from the capability name.
        assertEquals(2, plan.withdrawals().size(), plan.withdrawals().toString());
        assertEquals(2, plan.establishments().size(), plan.establishments().toString());
        assertTrue(
                plan.withdrawals().stream().allMatch(w -> w.relativePath().endsWith("Controller.java") || w.relativePath().endsWith("Service.java")),
                plan.withdrawals().toString());
    }

    @Test
    void p1ActorBearingCapabilityRenameIsRefusedBecauseAProjectFileChangesInPlace() throws Exception {
        // campus-market's PublishGoods has an actor: its route is rendered into Application.java,
        // whose path does not depend on the capability name. Renaming the capability therefore
        // changes Application.java content while keeping its path, and that path is outside the
        // renamed declaration's closure (SERVICE/CONTROLLER only). The planner must refuse it rather
        // than silently plan nothing for it.
        Revision base = revision("p1-market-base", MARKET_SOURCE_ID, MARKET_BASE);
        Revision candidate = revision("p1-market-candidate", MARKET_SOURCE_ID, MARKET_CANDIDATE);

        RenameAnalysis analysis = planOrReject(base, candidate, declaredCapability(base.model()));
        assertInstanceOf(RenameAnalysis.Rejected.class, analysis, () -> "expected a refusal: " + analysis);
        assertTrue(
                analysis.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals("SIR-RENAME-PATH-004")
                                && diagnostic.relativePath().isPresent()
                                && diagnostic.relativePath().get().endsWith("Application.java")),
                () -> "the path-stable file whose bytes changed must be named: " + analysis.diagnostics());
        assertTrue(
                analysis.diagnostics().stream().allMatch(d -> d.relativePath().isPresent()),
                analysis.diagnostics().toString());
    }

    // ---- P2 ------------------------------------------------------------------------------------

    @Test
    void p2EveryRealPlanEntryCarriesAnOwnerSymbol() throws Exception {
        Revision base = revision("p2-course-base", COURSE_SOURCE_ID, COURSE_BASE);
        Revision candidate = revision("p2-course-candidate", COURSE_SOURCE_ID, COURSE_CANDIDATE);
        RenamePlan plan = plan(base, candidate, declaredCapability(base.model()));

        for (var update : plan.updates()) {
            assertTrue(update.ownerSymbol().isPresent(), "update without owner: " + update.relativePath());
        }

        for (var withdrawal : plan.withdrawals()) {
            assertTrue(withdrawal.ownerSymbol().isPresent(), "withdrawal without owner: " + withdrawal.relativePath());
        }

        for (var establishment : plan.establishments()) {
            assertTrue(
                    establishment.ownerSymbol().isPresent(),
                    "establishment without owner: " + establishment.relativePath());
        }

        // The two real entries of each set are the renamed capability's own files, so their owner is
        // exactly the rename subject; the lists are non-empty, so this is a measured fact, not a
        // vacuous truth.
        assertFalse(plan.withdrawals().isEmpty());
        assertTrue(plan.withdrawals().stream().allMatch(w -> w.ownerSymbol().orElseThrow().equals(plan.subject().declarationSymbol())));
        assertTrue(plan.establishments().stream().allMatch(e -> e.ownerSymbol().orElseThrow().equals(plan.subject().declarationSymbol())));
    }

    // ---- P3 ------------------------------------------------------------------------------------

    @Test
    void p3HardLinkBackupSurvivesAnAtomicReplaceAndKeepsTheOldBytes() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "p3-update-");
        Path staging = Files.createDirectory(root.resolve("staging"));
        Path transactionDir = Files.createDirectory(root.resolve("tx"));
        byte[] baseBytes = "base-bytes\n".getBytes(UTF_8);
        byte[] candidateBytes = "candidate-bytes-are-longer\n".getBytes(UTF_8);

        Path target = root.resolve("target.java");
        Files.write(target, baseBytes);
        Path backup = transactionDir.resolve("f0.backup");
        // A second hard link witnesses the old inode once the target has been replaced.
        Path oldInodeWitness = transactionDir.resolve("f0.witness");
        Files.createLink(oldInodeWitness, target);

        Files.createLink(backup, target);
        assertTrue(Files.isSameFile(target, backup), "the backup hard link must alias the target");
        assertArrayEquals(baseBytes, Files.readAllBytes(backup), "the backup must read the B0 bytes");

        Path staged = staging.resolve("target.java");
        Files.write(staged, candidateBytes);

        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        assertArrayEquals(candidateBytes, Files.readAllBytes(target), "the atomic replace publishes the candidate bytes");
        assertFalse(Files.exists(staged, LinkOption.NOFOLLOW_LINKS), "the staged file must be gone after the move");
        assertFalse(Files.isSameFile(target, backup), "the target is a new inode after the replace");
        assertTrue(Files.isSameFile(backup, oldInodeWitness), "the backup still aliases the pre-replace inode");
        assertArrayEquals(baseBytes, Files.readAllBytes(backup), "the backup still holds the B0 bytes");
    }

    @Test
    void p3BothSubstitutionKindsAreDetectableAfterTheBackupLinkExists() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "p3-guards-");
        byte[] baseBytes = "base-bytes\n".getBytes(UTF_8);
        byte[] otherBytes = "somebody-elses-bytes\n".getBytes(UTF_8);

        // Kind 1: an outside writer replaces the target with a different inode.
        Path replaced = root.resolve("replaced.java");
        Files.write(replaced, baseBytes);
        Path replacedBackup = root.resolve("replaced.backup");
        Files.createLink(replacedBackup, replaced);
        Files.delete(replaced);
        Files.write(replaced, otherBytes);
        assertFalse(
                Files.isSameFile(replaced, replacedBackup),
                "replacing the target with a new inode must break the same-file proof");

        // Kind 2: an outside writer overwrites the target in place. The inode is unchanged, so the
        // same-file proof alone cannot see it — but the backup aliases the same inode and therefore
        // reads the new bytes, so the B0 digest re-check does see it.
        Path overwritten = root.resolve("overwritten.java");
        Files.write(overwritten, baseBytes);
        Path overwrittenBackup = root.resolve("overwritten.backup");
        Files.createLink(overwrittenBackup, overwritten);
        Files.write(overwritten, otherBytes, StandardOpenOption.TRUNCATE_EXISTING);
        assertTrue(
                Files.isSameFile(overwritten, overwrittenBackup),
                "an in-place overwrite keeps the inode, so isSameFile stays true");
        assertArrayEquals(
                otherBytes,
                Files.readAllBytes(overwrittenBackup),
                "an in-place overwrite is visible through the backup, which is what the B0 digest re-check catches");
    }

    @Test
    void p3AtomicMoveWithoutReplaceEitherReplacesOrFailsWithoutCorruption() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "p3-noreplace-");
        byte[] baseBytes = "base\n".getBytes(UTF_8);
        byte[] candidateBytes = "candidate\n".getBytes(UTF_8);
        Path target = root.resolve("target.java");
        Files.write(target, baseBytes);
        Path staged = root.resolve("staged.java");
        Files.write(staged, candidateBytes);

        boolean replacedAtomically = false;
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            replacedAtomically = true;
        } catch (IOException e) {
            // A filesystem that refuses to replace an existing target is a detectable failure, not
            // silent corruption: the target must still hold the base bytes.
            assertArrayEquals(baseBytes, Files.readAllBytes(target), "a refused move must leave the base bytes");
        }

        if (replacedAtomically) {
            assertArrayEquals(candidateBytes, Files.readAllBytes(target), "an accepted move must publish the candidate bytes");
        }
    }

    // ---- P4 ------------------------------------------------------------------------------------

    @Test
    void p4RealPlanPathsAreDisjointAndNotNestedSoNoOperationOrderIsForced() throws Exception {
        Revision base = revision("p4-course-base", COURSE_SOURCE_ID, COURSE_BASE);
        Revision candidate = revision("p4-course-candidate", COURSE_SOURCE_ID, COURSE_CANDIDATE);
        RenamePlan plan = plan(base, candidate, declaredCapability(base.model()));

        List<String> paths = new ArrayList<>();
        plan.updates().forEach(value -> paths.add(value.relativePath()));
        plan.withdrawals().forEach(value -> paths.add(value.relativePath()));
        plan.establishments().forEach(value -> paths.add(value.relativePath()));
        assertFalse(paths.isEmpty(), "the probe needs a non-empty path set");

        for (String a : paths) {
            for (String b : paths) {
                if (a.equals(b)) {
                    continue;
                }

                Path pa = Path.of(a);
                Path pb = Path.of(b);
                assertFalse(pa.startsWith(pb), "a plan path must not be an ancestor of another: " + a + " vs " + b);
                assertFalse(pb.startsWith(pa), "a plan path must not be an ancestor of another: " + b + " vs " + a);
            }
        }

        // A rename that stays inside the software's namespace reuses the directories that already
        // exist in the baseline tree, so no establishment needs a directory created first.
        Set<String> withdrawalParents = parents(plan.withdrawals().stream().map(value -> value.relativePath()).toList());
        Set<String> establishmentParents =
                parents(plan.establishments().stream().map(value -> value.relativePath()).toList());
        assertEquals(
                withdrawalParents,
                establishmentParents,
                "the rename stays inside the same directories, so order cannot matter for directory existence");
        for (String parent : establishmentParents) {
            assertTrue(
                    Files.isDirectory(base.root().resolve(parent)),
                    "the establishment directory already exists in the baseline: " + parent);
        }
    }

    // ---- pipeline helpers ----------------------------------------------------------------------

    private static Set<String> parents(List<String> relativePaths) {
        return relativePaths.stream()
                .map(relativePath -> Path.of(relativePath).getParent().toString().replace('\\', '/'))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private RenamePlan plan(Revision base, Revision candidate, SymbolId subject) {
        RenameAnalysis analysis = planOrReject(base, candidate, subject);
        assertInstanceOf(RenameAnalysis.Planned.class, analysis, () -> "expected a plan: " + analysis.diagnostics());
        return ((RenameAnalysis.Planned) analysis).plan();
    }

    private RenameAnalysis planOrReject(Revision base, Revision candidate, SymbolId subject) {
        return planner.plan(new RenamePlanningInput(
                base.model(),
                candidate.model(),
                base.snapshot(),
                candidate.snapshot(),
                new RenamePlanRequest(
                        new ChangeBaseRevision(
                                base.sourceId(),
                                base.source().sha256Hex(),
                                GraphVersion.V0_1,
                                base.graph().canonicalDigest(),
                                ProjectGraphCanonicalFormatVersion.V1),
                        subject)));
    }

    private Revision revision(String name, SourceId sourceId, String sourceText) throws IOException {
        Path source = tempDir.resolve(name + ".sir").toAbsolutePath();
        Files.writeString(source, sourceText, UTF_8);
        Path root = tempDir.resolve(name + "-out").toAbsolutePath();
        ToolchainResult result = new ToolchainApplication()
                .execute(new ToolchainRequest(source, sourceId, root, ConflictPolicy.FAIL_IF_EXISTS));
        if (!(result instanceof ToolchainResult.Success success)) {
            ToolchainResult.Failure failure = (ToolchainResult.Failure) result;
            throw new AssertionError("expected Success but got Failure at " + failure.failedStage() + ": "
                    + failure.diagnostics());
        }

        return new Revision(root, success.graph(), modelOf(sourceId, sourceText),
                RenameSourceSnapshot.of(sourceId, sourceText), sourceId);
    }

    private static NormalizedSemanticModel modelOf(SourceId sourceId, String sourceText) {
        ParseResult parsed = SirParser.create().parse(new SirSource(sourceId, sourceText));
        assertTrue(parsed.isSuccess(), () -> "fixture must parse: " + parsed.diagnostics());
        SemanticAnalysis analysis = new SirSemanticAnalyzer().analyze(parsed.document().orElseThrow());
        assertTrue(analysis.isSuccess(), () -> "fixture must analyze: " + analysis.diagnostics());
        return analysis.model().orElseThrow();
    }

    private static SymbolId declaredCapability(NormalizedSemanticModel model) {
        for (NormalizedDeclaration declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability
                    && capability.id().value().endsWith("/declared/capability/" + DECLARED_CAPABILITY_ID)) {
                return capability.id();
            }
        }

        throw new AssertionError("no capability with declared id " + DECLARED_CAPABILITY_ID + " in the model");
    }

    private static String courseSearchSource() {
        try {
            return ApplicationTestSupport.resource("valid/rename-course-search.sir");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The actor-bearing fixture with exactly one insertion: the declared id on {@code PublishGoods}.
     * The capability name is left alone here so the candidate side is the only rename in the edit.
     */
    private static String campusMarketWithDeclaredId() {
        String original;
        try {
            original = ApplicationTestSupport.resource("valid/campus-market.sir");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        String withId = original.replace(
                "capability PublishGoods {", "capability PublishGoods @id(\"" + DECLARED_CAPABILITY_ID + "\") {");
        assertFalse(withId.equals(original), "the declared id insertion must change the source");
        return withId;
    }

    /** One real revision: its root, graph, model and source snapshot. */
    private record Revision(
            Path root,
            ProjectGraph graph,
            NormalizedSemanticModel model,
            RenameSourceSnapshot source,
            SourceId sourceId) {
        RenameRevisionSnapshot snapshot() {
            return new RenameRevisionSnapshot(graph, source);
        }
    }
}
