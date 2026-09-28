package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.application.internal.state.RenamePlanBindingVerifier;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
import io.kcg.sir.change.api.RenameFileWithdrawal;
import io.kcg.sir.change.api.RenamePlan;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plan-to-manifest binding of Q17 seam A ({@link RenamePlanBindingVerifier}).
 *
 * <p>The positive case uses the real Q16 plan over the real fixture and the two real Bundle
 * manifests: the baseline's, built the way {@code BaselineBuilder.build} builds the descriptor
 * manifest, and the candidate's, built the same way from the candidate's generated files. The
 * refusal cases mutate exactly one thing - one plan entry or one manifest entry - and assert the
 * diagnostic code, the stage and the affected path, plus that the real trees on disk are untouched
 * by a refusal.
 *
 * <p>Mutated plans keep the original plan's identity fields ({@code basedOn}, {@code subject}, the
 * graph/source digests and {@code planDigest}). That is deliberate: this verifier binds the plan's
 * three sets to the manifests and does not look at the plan's identity - a stale plan is refused by
 * the Q16 layer ({@code RenamePlanner.verify}, {@code SIR-RENAME-STALE-*}) and the journal header
 * binding of {@code planDigest} belongs to the transaction, not to this bridge. The synthetic
 * three-set case exists because P1 measured that a real capability rename can never produce an
 * update entry: the update leg has synthetic evidence only.
 */
class RenamePlanBindingVerifierTest {

    /**
     * A real managed path the plan does not name and whose content survives both revisions: the
     * binding must still account for it, and its owner symbol is present (the generated
     * {@code Application.java} has none, so it cannot be used to build a plan entry).
     */
    private static final String GHOST_PATH = "src/main/java/com/example/rename/api/GhostController.java";
    private static final String SYNTHETIC_SURVIVOR = "src/main/java/com/example/synthetic/Kept.java";
    private static final String SYNTHETIC_UPDATE = "src/main/java/com/example/synthetic/Updated.java";
    private static final String SYNTHETIC_WITHDRAWAL = "src/main/java/com/example/synthetic/Old.java";
    private static final String SYNTHETIC_ESTABLISHMENT = "src/main/java/com/example/synthetic/New.java";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);
    private static final String SHA_E = "e".repeat(64);

    @TempDir
    static Path tempDir;

    private static RenamePreflightTestSupport.Revision base;
    private static RenamePreflightTestSupport.Revision candidate;
    private static RenamePlan plan;
    private static BaselineManifestEntry survivor;

    @BeforeAll
    static void fixture() throws IOException {
        base = RenamePreflightTestSupport.base(tempDir);
        candidate = RenamePreflightTestSupport.candidate(tempDir);
        plan = RenamePreflightTestSupport.plan(base, candidate);
        survivor = unchangedSurvivor();
    }

    // ---- the real revisions ------------------------------------------------------------------

    @Test
    void theRealPlanBindsToTheRealB0AndB1Manifests() throws IOException {
        Map<String, String> baseBefore = RenamePreflightTestSupport.inventory(base.root());
        Map<String, String> candidateBefore = RenamePreflightTestSupport.inventory(candidate.root());

        RenamePlanBindingVerifier.Result.Success success = assertInstanceOf(
                RenamePlanBindingVerifier.Result.Success.class,
                RenamePlanBindingVerifier.verify(base.manifest(), candidate.manifest(), plan),
                "the real plan must bind to the two real manifests");

        assertEquals(
                List.of(),
                plan.updates(),
                "P1: a capability rename moves both of its own files, so the real plan has no update entry");
        assertEquals(2, plan.withdrawals().size(), plan.withdrawals().toString());
        assertEquals(2, plan.establishments().size(), plan.establishments().toString());
        assertEquals(
                touched(plan),
                success.touchedPaths(),
                "the binding reports the managed paths the transaction must touch, ordered by path");

        // The manifests really are the two revisions' managed files: the withdrawn paths are held by
        // B0 and by nothing else, the established paths by B1 and by nothing else, and both manifests
        // also hold paths the plan does not name, so the partition check is not vacuous.
        Set<String> b0Paths = new LinkedHashSet<>(RenamePreflightTestSupport.pathsOf(base.manifest()));
        Set<String> b1Paths = new LinkedHashSet<>(RenamePreflightTestSupport.pathsOf(candidate.manifest()));
        assertTrue(b0Paths.containsAll(withdrawalPaths(plan.withdrawals())), b0Paths.toString());
        assertTrue(b1Paths.containsAll(establishmentPaths(plan.establishments())), b1Paths.toString());
        assertTrue(Collections.disjoint(b1Paths, withdrawalPaths(plan.withdrawals())), b1Paths.toString());
        assertTrue(Collections.disjoint(b0Paths, establishmentPaths(plan.establishments())), b0Paths.toString());
        assertTrue(
                b0Paths.size() > plan.withdrawals().size() + plan.establishments().size(),
                "B0 holds managed paths outside the plan, so binding had real survivors to check: " + b0Paths.size());
        assertEquals(b0Paths.size(), b1Paths.size(), "a rename retires as many managed files as it establishes");

        assertEquals(baseBefore, RenamePreflightTestSupport.inventory(base.root()), "binding must not touch the baseline tree");
        assertEquals(candidateBefore, RenamePreflightTestSupport.inventory(candidate.root()), "binding must not touch the candidate tree");
    }

    @Test
    void aSyntheticThreeSetPlanBindsWhenEveryEntryMatchesBothManifests() {
        // P1: the update leg has no real pipeline evidence, so the three-set case is synthetic. The
        // entries carry the same identity on both sides and the manifests hold exactly the three
        // changes plus one unchanged survivor.
        SymbolId owner = plan.subject().declarationSymbol();
        LoweredNodeId survivorArtifact = new LoweredNodeId("lir://synthetic/artifact-survivor");
        LoweredNodeId updateArtifact = new LoweredNodeId("lir://synthetic/artifact-update");
        LoweredNodeId withdrawalArtifact = new LoweredNodeId("lir://synthetic/artifact-withdrawal");
        LoweredNodeId establishmentArtifact = new LoweredNodeId("lir://synthetic/artifact-establishment");
        List<BaselineManifestEntry> b0 = List.of(
                entry(SYNTHETIC_UPDATE, 11L, SHA_A, updateArtifact, owner),
                entry(SYNTHETIC_WITHDRAWAL, 12L, SHA_B, withdrawalArtifact, owner),
                entry(SYNTHETIC_SURVIVOR, 13L, SHA_C, survivorArtifact, owner));
        List<BaselineManifestEntry> b1 = List.of(
                entry(SYNTHETIC_ESTABLISHMENT, 14L, SHA_D, establishmentArtifact, owner),
                entry(SYNTHETIC_UPDATE, 15L, SHA_E, updateArtifact, owner),
                entry(SYNTHETIC_SURVIVOR, 13L, SHA_C, survivorArtifact, owner));
        RenamePlan synthetic = planWith(
                List.of(new RenameFileUpdate(SYNTHETIC_UPDATE, updateArtifact, Optional.of(owner), 11L, SHA_A, 15L, SHA_E)),
                List.of(new RenameFileWithdrawal(SYNTHETIC_WITHDRAWAL, withdrawalArtifact, Optional.of(owner), 12L, SHA_B)),
                List.of(new RenameFileEstablishment(SYNTHETIC_ESTABLISHMENT, establishmentArtifact, Optional.of(owner), 14L, SHA_D)));

        RenamePlanBindingVerifier.Result.Success success = assertInstanceOf(
                RenamePlanBindingVerifier.Result.Success.class,
                RenamePlanBindingVerifier.verify(b0, b1, synthetic),
                "a three-set plan whose entries match both manifests must bind");

        assertEquals(
                List.of(SYNTHETIC_ESTABLISHMENT, SYNTHETIC_WITHDRAWAL, SYNTHETIC_UPDATE),
                success.touchedPaths(),
                "the three sets are reported as one path-ordered list");
    }

    // ---- one plan entry missing its owner ----------------------------------------------------

    @Test
    void anUpdateEntryWithoutAnOwnerSymbolIsRefusedWithTheFrozenCode() throws IOException {
        RenameFileUpdate orphaned = new RenameFileUpdate(GHOST_PATH, new LoweredNodeId("lir://synthetic/artifact-update"), Optional.empty(), 11L, SHA_A, 12L, SHA_B);
        RenamePlan mutated = planWith(List.of(orphaned), List.of(), List.of());

        ChangeExecutionDiagnostic error = refusal(mutated, "SIR-APP-RENAME-BIND-001");
        assertEquals(Optional.of(GHOST_PATH), error.relativePath(), error.message());
    }

    @Test
    void aWithdrawalEntryWithoutAnOwnerSymbolIsRefusedWithTheFrozenCode() throws IOException {
        RenameFileWithdrawal original = plan.withdrawals().get(0);
        RenameFileWithdrawal orphaned = new RenameFileWithdrawal(
                original.relativePath(), original.artifactId(), Optional.empty(), original.byteCount(), original.sha256Hex());
        List<RenameFileWithdrawal> withdrawals = new ArrayList<>(plan.withdrawals());
        withdrawals.set(0, orphaned);
        RenamePlan mutated = planWith(plan.updates(), withdrawals, plan.establishments());

        ChangeExecutionDiagnostic error = refusal(mutated, "SIR-APP-RENAME-BIND-001");
        assertEquals(Optional.of(original.relativePath()), error.relativePath(), error.message());
    }

    @Test
    void anEstablishmentEntryWithoutAnOwnerSymbolIsRefusedWithTheFrozenCode() throws IOException {
        RenameFileEstablishment original = plan.establishments().get(0);
        RenameFileEstablishment orphaned = new RenameFileEstablishment(
                original.relativePath(), original.artifactId(), Optional.empty(), original.byteCount(), original.sha256Hex());
        List<RenameFileEstablishment> establishments = new ArrayList<>(plan.establishments());
        establishments.set(0, orphaned);
        RenamePlan mutated = planWith(plan.updates(), plan.withdrawals(), establishments);

        ChangeExecutionDiagnostic error = refusal(mutated, "SIR-APP-RENAME-BIND-001");
        assertEquals(Optional.of(original.relativePath()), error.relativePath(), error.message());
    }

    // ---- one plan entry that no manifest holds -----------------------------------------------

    @Test
    void aPlanPathNoManifestHoldsIsRefused() throws IOException {
        RenameFileWithdrawal original = plan.withdrawals().get(0);
        RenameFileWithdrawal ghost = new RenameFileWithdrawal(
                GHOST_PATH, original.artifactId(), original.ownerSymbol(), original.byteCount(), original.sha256Hex());
        List<RenameFileWithdrawal> withdrawals = new ArrayList<>(plan.withdrawals());
        withdrawals.set(0, ghost);
        RenamePlan mutated = planWith(plan.updates(), withdrawals, plan.establishments());

        ChangeExecutionDiagnostic error = refusal(mutated, "SIR-APP-RENAME-BIND-003");
        assertEquals(Optional.of(GHOST_PATH), error.relativePath(), error.message());
        assertTrue(error.message().contains("B0"), error.message());
    }

    // ---- identity and content cross-checks ---------------------------------------------------

    @Test
    void aPlannedWithdrawalWhoseManifestContentDiffersIsRefused() throws IOException {
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        BaselineManifestEntry original = base.manifestByPath().get(withdrawal.relativePath());
        List<BaselineManifestEntry> mutated = replace(
                base.manifest(),
                original.relativePath(),
                entry(original.relativePath(), original.byteCount() + 1L, SHA_E, original.artifactId(), original.ownerSymbol().get()));

        ChangeExecutionDiagnostic error = refusal(mutated, candidate.manifest(), plan, "SIR-APP-RENAME-BIND-005");
        assertEquals(Optional.of(withdrawal.relativePath()), error.relativePath(), error.message());
        assertTrue(error.message().contains("manifestBytes=" + (original.byteCount() + 1L)), error.message());
    }

    @Test
    void aPlannedEstablishmentWhoseManifestIdentityDiffersIsRefused() throws IOException {
        RenameFileEstablishment establishment = plan.establishments().get(0);
        BaselineManifestEntry original = candidate.manifestByPath().get(establishment.relativePath());
        List<BaselineManifestEntry> mutated = replace(
                candidate.manifest(),
                original.relativePath(),
                entry(original.relativePath(), original.byteCount(), original.sha256Hex(),
                        new LoweredNodeId("lir://synthetic/artifact-someone-else"), original.ownerSymbol().get()));

        ChangeExecutionDiagnostic error = refusal(base.manifest(), mutated, plan, "SIR-APP-RENAME-BIND-004");
        assertEquals(Optional.of(establishment.relativePath()), error.relativePath(), error.message());
        assertTrue(error.message().contains("identity mismatch"), error.message());
    }

    @Test
    void aWithdrawalEntryWhoseManifestOwnerSymbolIsAbsentIsRefused() throws IOException {
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        BaselineManifestEntry original = base.manifestByPath().get(withdrawal.relativePath());
        List<BaselineManifestEntry> mutated = replace(
                base.manifest(),
                original.relativePath(),
                new BaselineManifestEntry(original.relativePath(), original.byteCount(), original.sha256Hex(), original.artifactId(), Optional.empty()));

        ChangeExecutionDiagnostic error = refusal(mutated, candidate.manifest(), plan, "SIR-APP-RENAME-BIND-004");
        assertEquals(Optional.of(withdrawal.relativePath()), error.relativePath(), error.message());
        assertTrue(error.message().contains("no owner symbol"), error.message());
    }

    // ---- the manifest delta must be exactly the plan's three sets ----------------------------

    @Test
    void aWithdrawalThePlanOmitsIsRefused() throws IOException {
        RenamePlan mutated = planWith(plan.updates(), List.of(plan.withdrawals().get(0)), plan.establishments());

        ChangeExecutionDiagnostic error = refusal(mutated, "SIR-APP-RENAME-BIND-006");
        assertTrue(error.relativePath().isPresent(), error.message());
        assertTrue(error.message().contains("planned withdrawals"), error.message());
    }

    @Test
    void aPlanThatWithdrawsAPathBothManifestsKeepIsRefused() throws IOException {
        RenameFileWithdrawal overReported = new RenameFileWithdrawal(
                survivor.relativePath(), survivor.artifactId(), survivor.ownerSymbol(), survivor.byteCount(), survivor.sha256Hex());
        List<RenameFileWithdrawal> withdrawals = new ArrayList<>(plan.withdrawals());
        withdrawals.add(overReported);
        withdrawals.sort((left, right) -> left.relativePath().compareTo(right.relativePath()));
        RenamePlan mutated = planWith(plan.updates(), withdrawals, plan.establishments());

        ChangeExecutionDiagnostic error = refusal(mutated, "SIR-APP-RENAME-BIND-006");
        assertEquals(Optional.of(survivor.relativePath()), error.relativePath(), error.message());
    }

    @Test
    void aManagedPathWhoseContentChangedWithoutAPlanEntryIsRefused() throws IOException {
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        List<BaselineManifestEntry> mutated = replace(
                candidate.manifest(),
                survivor.relativePath(),
                entry(survivor.relativePath(), withdrawal.byteCount(), withdrawal.sha256Hex(), survivor.artifactId(), survivor.ownerSymbol().get()));

        ChangeExecutionDiagnostic error = refusal(base.manifest(), mutated, plan, "SIR-APP-RENAME-BIND-006");
        assertEquals(Optional.of(survivor.relativePath()), error.relativePath(), error.message());
        assertTrue(error.message().contains("changed in place"), error.message());
    }

    @Test
    void aNewManagedPathWithoutAPlanEntryIsRefused() throws IOException {
        RenameFileEstablishment establishment = plan.establishments().get(0);
        List<BaselineManifestEntry> mutated = new ArrayList<>(candidate.manifest());
        mutated.add(entry(GHOST_PATH, 21L, SHA_B, establishment.artifactId(), establishment.ownerSymbol().get()));

        ChangeExecutionDiagnostic error = refusal(base.manifest(), mutated, plan, "SIR-APP-RENAME-BIND-006");
        assertEquals(Optional.of(GHOST_PATH), error.relativePath(), error.message());
        assertTrue(error.message().contains("planned establishments"), error.message());
    }

    @Test
    void aContentUnchangedManagedPathThatDriftedIsRefused() throws IOException {
        BaselineManifestEntry changed = candidate.manifestByPath().get(survivor.relativePath());
        List<BaselineManifestEntry> mutated = replace(
                candidate.manifest(),
                survivor.relativePath(),
                entry(
                        survivor.relativePath(),
                        changed.byteCount(),
                        changed.sha256Hex(),
                        new LoweredNodeId("lir://synthetic/artifact-impostor"),
                        new SymbolId("sir://RenameCourseSearch/declared/capability/impostor")));

        ChangeExecutionDiagnostic error = refusal(base.manifest(), mutated, plan, "SIR-APP-RENAME-BIND-007");
        assertEquals(Optional.of(survivor.relativePath()), error.relativePath(), error.message());
        assertTrue(error.message().contains("drifted"), error.message());
    }

    @Test
    void aDuplicateManagedPathInAManifestIsRefused() throws IOException {
        List<BaselineManifestEntry> mutated = new ArrayList<>(base.manifest());
        mutated.add(survivor);

        ChangeExecutionDiagnostic error = refusal(mutated, candidate.manifest(), plan, "SIR-APP-RENAME-BIND-002");
        assertTrue(error.message().contains("duplicate relativePath in B0"), error.message());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static BaselineManifestEntry unchangedSurvivor() {
        Set<String> named = new LinkedHashSet<>(touched(plan));
        for (BaselineManifestEntry entry : base.manifest()) {
            if (named.contains(entry.relativePath()) || entry.ownerSymbol().isEmpty()) {
                continue;
            }

            BaselineManifestEntry onCandidateSide = candidate.manifestByPath().get(entry.relativePath());
            if (entry.equals(onCandidateSide)) {
                return entry;
            }
        }

        throw new AssertionError("the fixture must hold an unchanged managed path with an owner outside the plan");
    }

    private static ChangeExecutionDiagnostic refusal(RenamePlan mutatedPlan, String expectedCode) throws IOException {
        return refusal(base.manifest(), candidate.manifest(), mutatedPlan, expectedCode);
    }

    private static ChangeExecutionDiagnostic refusal(
            List<BaselineManifestEntry> b0, List<BaselineManifestEntry> b1, RenamePlan mutatedPlan, String expectedCode) throws IOException {
        Map<String, String> baseBefore = RenamePreflightTestSupport.inventory(base.root());
        Map<String, String> candidateBefore = RenamePreflightTestSupport.inventory(candidate.root());

        RenamePlanBindingVerifier.Result result = RenamePlanBindingVerifier.verify(b0, b1, mutatedPlan);
        RenamePlanBindingVerifier.Result.Failure failure = assertInstanceOf(
                RenamePlanBindingVerifier.Result.Failure.class, result, () -> "expected a refusal but bound: " + result);
        assertEquals(expectedCode, failure.error().code(), failure.error().message());
        assertEquals(ChangeExecutionStage.PROTECT, failure.error().stage(), "a binding refusal is reported at the PROTECT stage");
        assertTrue(failure.error().isError(), "a binding refusal must be an error");

        assertEquals(baseBefore, RenamePreflightTestSupport.inventory(base.root()), "a refused binding must not touch the baseline tree");
        assertEquals(
                candidateBefore, RenamePreflightTestSupport.inventory(candidate.root()), "a refused binding must not touch the candidate tree");
        return failure.error();
    }

    /**
     * Rebuild the plan with a mutated managed-file delta, keeping its identity fields: this verifier
     * binds the delta to the manifests and reads none of {@code basedOn}/{@code subject}/digests.
     */
    private static RenamePlan planWith(
            List<RenameFileUpdate> updates, List<RenameFileWithdrawal> withdrawals, List<RenameFileEstablishment> establishments) {
        return new RenamePlan(
                plan.basedOn(),
                plan.subject(),
                updates,
                withdrawals,
                establishments,
                plan.baseGraphCanonicalDigest(),
                plan.candidateGraphCanonicalDigest(),
                plan.baseSourceSha256Hex(),
                plan.candidateSourceSha256Hex(),
                plan.planDigest());
    }

    private static BaselineManifestEntry entry(
            String relativePath, long byteCount, String sha256Hex, LoweredNodeId artifactId, SymbolId ownerSymbol) {
        return new BaselineManifestEntry(relativePath, byteCount, sha256Hex, artifactId, Optional.of(ownerSymbol));
    }

    private static List<BaselineManifestEntry> replace(
            List<BaselineManifestEntry> manifest, String relativePath, BaselineManifestEntry replacement) {
        List<BaselineManifestEntry> mutated = new ArrayList<>(manifest);
        for (int index = 0; index < mutated.size(); index++) {
            if (mutated.get(index).relativePath().equals(relativePath)) {
                mutated.set(index, replacement);
                return mutated;
            }
        }

        throw new AssertionError("no manifest entry for " + relativePath);
    }

    private static List<String> withdrawalPaths(List<RenameFileWithdrawal> withdrawals) {
        return withdrawals.stream().map(RenameFileWithdrawal::relativePath).toList();
    }

    private static List<String> establishmentPaths(List<RenameFileEstablishment> establishments) {
        return establishments.stream().map(RenameFileEstablishment::relativePath).toList();
    }

    private static List<String> touched(RenamePlan plan) {
        Set<String> paths = new TreeSet<>();
        plan.updates().forEach(update -> paths.add(update.relativePath()));
        plan.withdrawals().forEach(withdrawal -> paths.add(withdrawal.relativePath()));
        plan.establishments().forEach(establishment -> paths.add(establishment.relativePath()));
        return List.copyOf(paths);
    }
}
