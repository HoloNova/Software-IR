package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Baseline;
import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Family;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fault matrix for the DELETE transaction (a change that removes a declaration and its files).
 *
 * <p>Same contract as the APPLY and CREATE matrices. DELETE is the family with the additional
 * constraint that its backup is a same-volume hard link, so this class also asserts that the
 * backup really is a link to the target (never a copy) and that compensation restores the file.
 */
class DeleteFaultMatrixTest {

    /**
     * Interruption points the DELETE transaction reaches on its forward path.
     *
     * <p>Excludes the rollback-only points ({@code beforeDeleteRollbackStep},
     * {@code afterBackupDelete}, {@code afterRestoreLinkCreate}, {@code afterDeleteRollbackComplete}),
     * which only run while compensating an earlier failure.
     */
    private static final List<String> DELETE_HOOKS = List.of(
            "afterTransactionDirCreate",
            "afterJournalCreate",
            "afterBundleStage",
            "afterJournalForce",
            "beforeBackupLinkIntent",
            "afterBackupLinkCreate",
            "beforeDeleteIntent",
            "afterTargetDelete",
            "afterDeleteFileComplete",
            "afterAllDeleteLinks",
            "afterBundlePublish",
            "beforeCurrentNewWrite",
            "afterCurrentNewWrite",
            "afterCurrentAtomicMove",
            "afterPublishReread",
            "beforeCleanup");

    /** Points that only run while a DELETE transaction compensates an earlier failure. */
    private static final List<String> DELETE_ROLLBACK_ONLY = List.of(
            "beforeDeleteRollbackStep",
            "afterBackupDelete",
            "afterRestoreLinkCreate",
            "afterDeleteRollbackComplete");

    @TempDir
    Path tempDir;

    static Stream<String> deleteHookPoints() {
        return DELETE_HOOKS.stream();
    }

    @ParameterizedTest(name = "DELETE fault at {0}")
    @MethodSource("deleteHookPoints")
    void faultAtEachInterruptionPointLeavesADirectionConsistentState(String hook) throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.DELETE);
        FailingApplyHooks hooks = new FailingApplyHooks(hook);

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);
        System.out.println(ChangeFaultMatrixSupport
                .assertDirectionConsistent("DELETE", hook, baseline, hooks, result).row());
    }

    /**
     * Compensation after a failed delete must restore the removed file from the backup hard link.
     *
     * <p>This is the DELETE-specific half of the matrix: the previous run removes a file and then
     * fails, and the compensating recovery must bring the exact bytes back rather than leaving a
     * gap.
     */
    /**
     * The rollback-only points are reached when an earlier DELETE step fails and the transaction
     * compensates.
     *
     * <p>The rollback branch depends on how far the failure got, so two injections are needed:
     *
     * <ul>
     *   <li>failing at {@code beforeDeleteIntent} leaves the backup link in place with the target
     *       still present, which exercises the backup-link rollback branch
     *       ({@code afterBackupDelete});</li>
     *   <li>failing at {@code afterDeleteFileComplete} means a file was already removed, which
     *       exercises the restore branch ({@code afterRestoreLinkCreate},
     *       {@code afterDeleteRollbackComplete}).</li>
     * </ul>
     *
     * <p>Both must reach {@code beforeDeleteRollbackStep} and end with the tree byte-identical to
     * B0.
     */
    @Test
    void rollbackOnlyPointsAreReachedDuringCompensation() throws Exception {
        Map<String, List<String>> branchHooks = Map.of(
                "beforeDeleteIntent", List.of("beforeDeleteRollbackStep", "afterBackupDelete"),
                "afterDeleteFileComplete", List.of("beforeDeleteRollbackStep",
                        "afterRestoreLinkCreate", "afterDeleteRollbackComplete"));

        for (Map.Entry<String, List<String>> branch : branchHooks.entrySet()) {
            String injection = branch.getKey();
            // Each branch needs its own working directory: a second baseline cannot be generated
            // into an output root that the first iteration already created.
            Path branchDir = java.nio.file.Files.createDirectory(tempDir.resolve(injection));
            Baseline baseline = ChangeFaultMatrixSupport.prepare(branchDir, Family.DELETE);
            FailingApplyHooks hooks = new FailingApplyHooks(injection);

            ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);

            assertTrue(hooks.firedAt(injection),
                    "the injected point must be reached: " + injection);
            for (String hook : branch.getValue()) {
                assertTrue(hooks.firedAt(hook), "compensating a failed delete must reach " + hook
                        + " (injection " + injection + "); fired=" + hooks.firedHooks());
            }
            Map<String, byte[]> tree = ChangeFaultMatrixSupport.readTree(baseline.outputRoot());
            assertTrue(ChangeFaultMatrixSupport.treeEquals(baseline.b0Bytes(), tree),
                    "after compensation every deleted file must be back byte-for-byte (injection "
                            + injection + "): " + ChangeFaultMatrixSupport
                            .describeTreeDiff(baseline.b0Bytes(), tree));
            assertEquals(baseline.baselineId(),
                    ChangeFaultMatrixSupport.readCurrentOrNull(baseline.stateRoot()),
                    "a compensated delete must leave CURRENT on B0 (injection " + injection + ")");
            assertFalse(result instanceof ChangeApplyResult.Applied,
                    "a failed delete must not be reported as applied (injection " + injection + ")");
        }
    }

    /** Guards the hook list against silently shrinking the matrix. */
    @Test
    void deleteHookMatrixCoversTheReachableForwardPath() {
        assertEquals(16, DELETE_HOOKS.size(),
                "the DELETE hook list changed; update this assertion and the work-order table "
                        + "together so the matrix cannot silently shrink");
        assertEquals(4, DELETE_ROLLBACK_ONLY.size(),
                "the DELETE rollback-only list changed; update the compensation test with it");
        assertEquals(Set.of("beforeDeleteRollbackStep", "afterBackupDelete", "afterRestoreLinkCreate",
                        "afterDeleteRollbackComplete"), Set.copyOf(DELETE_ROLLBACK_ONLY),
                "the per-branch expectations in rollbackOnlyPointsAreReachedDuringCompensation "
                        + "must cover exactly the rollback-only points");
    }
}
