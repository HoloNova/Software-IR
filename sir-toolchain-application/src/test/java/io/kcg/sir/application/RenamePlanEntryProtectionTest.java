package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.application.internal.PlanProtector;
import io.kcg.sir.change.api.RenameFileEstablishment;
import io.kcg.sir.change.api.RenameFileUpdate;
import io.kcg.sir.change.api.RenameFileWithdrawal;
import io.kcg.sir.change.api.RenamePlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The on-disk pre-flight of Q17 seam A: {@link PlanProtector}'s plan-entry forms.
 *
 * <p>The three plan-entry forms replace the three existing ones for a rename plan's entries -
 * {@link PlanProtector#protectUpdates} for {@code RenameFileUpdate},
 * {@link PlanProtector#protectWithdrawals} for {@code RenameFileWithdrawal} and
 * {@link PlanProtector#protectEstablishments} for {@code RenameFileEstablishment} - without turning a
 * plan entry into a {@code FileChange}/{@code FileDeletion}/{@code FileAddition}, whose owner symbol
 * is not optional. They keep the existing diagnostic codes and the stage the existing forms report,
 * so a caller maps them to PROTECT exactly as it does today.
 *
 * <p>Every refusal case mutates the real generated baseline tree (a real copy of it, so the fixture
 * tree stays intact) and asserts the code, the stage, the affected path and that the check left the
 * tree byte-for-byte untouched. The positive case asserts the plan's own digests against the files
 * on disk, so "clean" cannot be vacuous.
 */
class RenamePlanEntryProtectionTest {

    @TempDir
    static Path tempDir;

    @TempDir
    Path testDir;

    private static RenamePreflightTestSupport.Revision base;
    private static RenamePreflightTestSupport.Revision candidate;
    private static RenamePlan plan;

    @BeforeAll
    static void fixture() throws IOException {
        base = RenamePreflightTestSupport.base(tempDir);
        candidate = RenamePreflightTestSupport.candidate(tempDir);
        plan = RenamePreflightTestSupport.plan(base, candidate);
    }

    // ---- the real plan against the real baseline tree ----------------------------------------

    @Test
    void theRealPlanEntriesPassAgainstTheGeneratedBaselineTree() throws IOException {
        assertEquals(2, plan.withdrawals().size(), plan.withdrawals().toString());
        assertEquals(2, plan.establishments().size(), plan.establishments().toString());
        assertTrue(plan.updates().isEmpty(), "P1: the real plan has no update entry");

        Map<String, String> before = RenamePreflightTestSupport.inventory(base.root());
        assertEquals(
                List.of(),
                PlanProtector.protectUpdates(base.root(), plan.updates()),
                "an empty update set has nothing to protect");
        assertEquals(
                List.of(), PlanProtector.protectWithdrawals(base.root(), plan.withdrawals()), "the withdrawals are the baseline's bytes");
        assertEquals(
                List.of(),
                PlanProtector.protectEstablishments(base.root(), plan.establishments()),
                "the establishment paths are still free in the baseline tree");
        assertEquals(before, RenamePreflightTestSupport.inventory(base.root()), "a passing check must not modify the tree");

        // Non-vacuous: the clean result was earned by the real files and the real digests.
        for (RenameFileWithdrawal withdrawal : plan.withdrawals()) {
            assertEquals(
                    withdrawal.sha256Hex(),
                    RenamePreflightTestSupport.digestOfFile(base.root().resolve(withdrawal.relativePath())),
                    "the plan's withdrawal digest must be the file on disk: " + withdrawal.relativePath());
        }

        for (RenameFileEstablishment establishment : plan.establishments()) {
            assertFalse(
                    Files.exists(base.root().resolve(establishment.relativePath()), LinkOption.NOFOLLOW_LINKS),
                    "the plan's establishment path must be free: " + establishment.relativePath());
        }
    }

    @Test
    void thePlanEntriesRefuseTheTreeThatAlreadyHoldsTheCandidate() throws IOException {
        // The mirror direction, on the real candidate tree: its withdrawals are gone and its
        // establishments are present, so both forms must refuse every entry of the set.
        List<ExecutionDiagnostic> missing = PlanProtector.protectWithdrawals(candidate.root(), plan.withdrawals());
        assertEquals(2, missing.size(), missing.toString());
        assertTrue(missing.stream().allMatch(diagnostic -> diagnostic.code().equals("SIR-APP-CHANGE-PROTECT-107")), missing.toString());

        List<ExecutionDiagnostic> occupied = PlanProtector.protectEstablishments(candidate.root(), plan.establishments());
        assertEquals(2, occupied.size(), occupied.toString());
        assertTrue(occupied.stream().allMatch(diagnostic -> diagnostic.code().equals("SIR-APP-CHANGE-PROTECT-111")), occupied.toString());
        assertTrue(
                occupied.stream().allMatch(diagnostic -> diagnostic.severity() == ExecutionSeverity.ERROR),
                occupied.toString());
    }

    // ---- the update form, driven with a real base pair ---------------------------------------

    @Test
    void anUpdateChecksBaseBytesEvenWhenTheDiskAlreadyHoldsCandidateBytes() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        byte[] original = RenamePreflightTestSupport.bytesOf(root, withdrawal.relativePath());
        byte[] candidateBytes = original.clone();
        candidateBytes[candidateBytes.length - 1] ^= 0x01;
        RenamePreflightTestSupport.writeBytes(root, withdrawal.relativePath(), candidateBytes);
        String candidateSha = RenamePreflightTestSupport.digestOfFile(root.resolve(withdrawal.relativePath()));
        RenamePreflightTestSupport.writeBytes(root, withdrawal.relativePath(), original);
        assertFalse(candidateSha.equals(withdrawal.sha256Hex()), "the two sides must be distinguishable");

        RenameFileUpdate update = new RenameFileUpdate(
                withdrawal.relativePath(),
                withdrawal.artifactId(),
                withdrawal.ownerSymbol(),
                withdrawal.byteCount(),
                withdrawal.sha256Hex(),
                candidateBytes.length,
                candidateSha);
        assertAccepted(root, () -> PlanProtector.protectUpdates(root, List.of(update)));

        // An external writer putting exactly the candidate bytes on disk must still be refused:
        // pre-flight guards the B0 bytes, not the bytes the transaction intends to write.
        RenamePreflightTestSupport.writeBytes(root, withdrawal.relativePath(), candidateBytes);
        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-109",
                withdrawal.relativePath(),
                () -> PlanProtector.protectUpdates(root, List.of(update)));

        RenamePreflightTestSupport.writeBytes(root, withdrawal.relativePath(), Arrays.copyOf(original, original.length - 1));
        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-108",
                withdrawal.relativePath(),
                () -> PlanProtector.protectUpdates(root, List.of(update)));
    }

    // ---- a withdrawal path the transaction must not retire blindly ---------------------------

    @Test
    void aTamperedWithdrawalPathIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        byte[] bytes = RenamePreflightTestSupport.bytesOf(root, withdrawal.relativePath());
        byte[] substituted = Arrays.copyOf(bytes, bytes.length);
        substituted[0] = substituted[0] == 'p' ? (byte) 'q' : (byte) 'p';
        RenamePreflightTestSupport.writeBytes(root, withdrawal.relativePath(), substituted);

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-109",
                withdrawal.relativePath(),
                () -> PlanProtector.protectWithdrawals(root, plan.withdrawals()));
    }

    @Test
    void aWithdrawalPathThatIsGoneIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        Files.delete(root.resolve(withdrawal.relativePath()));

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-107",
                withdrawal.relativePath(),
                () -> PlanProtector.protectWithdrawals(root, plan.withdrawals()));
    }

    @Test
    void aWithdrawalPathReplacedByADirectoryIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        Files.delete(root.resolve(withdrawal.relativePath()));
        Files.createDirectory(root.resolve(withdrawal.relativePath()));

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-107",
                withdrawal.relativePath(),
                () -> PlanProtector.protectWithdrawals(root, plan.withdrawals()));
    }

    @Test
    void aWithdrawalPathReplacedByASymbolicLinkIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileWithdrawal withdrawal = plan.withdrawals().get(0);
        Path target = root.resolve(withdrawal.relativePath());
        Path elsewhere = root.resolve(plan.withdrawals().get(1).relativePath());
        Files.delete(target);
        createSymbolicLink(target, elsewhere);

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-106",
                withdrawal.relativePath(),
                () -> PlanProtector.protectWithdrawals(root, plan.withdrawals()));
    }

    // ---- an establishment path an untracked file already occupies ----------------------------

    @Test
    void anUntrackedFileOccupyingAnEstablishmentPathIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileEstablishment establishment = plan.establishments().get(0);
        RenamePreflightTestSupport.writeBytes(root, establishment.relativePath(), "// somebody else's file\n".getBytes(UTF_8));

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-111",
                establishment.relativePath(),
                () -> PlanProtector.protectEstablishments(root, plan.establishments()));
    }

    @Test
    void aDirectoryOccupyingAnEstablishmentPathIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileEstablishment establishment = plan.establishments().get(0);
        Files.createDirectory(root.resolve(establishment.relativePath()));

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-111",
                establishment.relativePath(),
                () -> PlanProtector.protectEstablishments(root, plan.establishments()));
    }

    @Test
    void aSymbolicLinkOccupyingAnEstablishmentPathIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileEstablishment establishment = plan.establishments().get(0);
        Path target = root.resolve(establishment.relativePath());
        createSymbolicLink(target, root.resolve(plan.withdrawals().get(0).relativePath()));

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-106",
                establishment.relativePath(),
                () -> PlanProtector.protectEstablishments(root, plan.establishments()));
    }

    @Test
    void aSymbolicLinkInAnEstablishmentParentDirectoryIsRefused() throws IOException {
        Path root = copyOfBaselineTree();
        RenameFileEstablishment establishment = plan.establishments().get(0);
        Path parent = root.resolve(Path.of(establishment.relativePath()).getParent());
        Path realParent = parent.resolveSibling(parent.getFileName().toString() + "-real");
        Files.move(parent, realParent);
        createSymbolicLink(parent, realParent);

        assertRefused(
                root,
                "SIR-APP-CHANGE-PROTECT-106",
                establishment.relativePath(),
                () -> PlanProtector.protectEstablishments(root, plan.establishments()));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Path copyOfBaselineTree() throws IOException {
        return RenamePreflightTestSupport.copyTree(base.root(), testDir.resolve("tree"));
    }

    private static void createSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "this platform cannot create a symbolic link: " + e);
        }
    }

    private static void assertAccepted(Path root, Supplier<List<ExecutionDiagnostic>> protection) throws IOException {
        Map<String, String> before = RenamePreflightTestSupport.inventory(root);
        assertEquals(List.of(), protection.get(), "the entry must pass against the base bytes");
        assertEquals(before, RenamePreflightTestSupport.inventory(root), "a passing check must not modify the tree");
    }

    private static void assertRefused(Path root, String code, String relativePath, Supplier<List<ExecutionDiagnostic>> protection) throws IOException {
        Map<String, String> before = RenamePreflightTestSupport.inventory(root);
        List<ExecutionDiagnostic> diagnostics = protection.get();
        assertEquals(before, RenamePreflightTestSupport.inventory(root), "a refused check must not modify the tree");
        assertEquals(List.of(code), diagnostics.stream().map(ExecutionDiagnostic::code).toList(), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.severity() == ExecutionSeverity.ERROR), diagnostics.toString());
        assertTrue(
                diagnostics.stream().allMatch(diagnostic -> diagnostic.stage() == ExecutionStage.GRAPH),
                "the plan-entry forms report the stage the existing three forms use, and the caller maps it to PROTECT: " + diagnostics);
        assertTrue(
                diagnostics.stream().allMatch(diagnostic -> diagnostic.message().contains(relativePath)),
                "the diagnostic must name the affected path: " + diagnostics);
    }
}
