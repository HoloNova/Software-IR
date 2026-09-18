package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Baseline;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fault matrix for the APPLY (UPDATE) transaction: one injected failure at every interruption
 * point, with the terminal state asserted against the ADR-020 direction rules.
 *
 * <p>Each case injects exactly one failure through {@link FailingApplyHooks} and then asserts:
 *
 * <ul>
 *   <li><b>V1</b> the injected hook was actually reached — without this every case would pass
 *       vacuously if a hook were renamed or became unreachable;</li>
 *   <li><b>V2</b> CURRENT still exists and no {@code CURRENT.new} is left behind by a successful
 *       run: the pointer is never lost, and a success never leaves a half-published pointer;</li>
 *   <li><b>V3</b> the output tree is never a half-applied mix attributed to a completed state: if
 *       CURRENT still points at B0, the tree must be byte-identical to B0 <em>unless</em> the run
 *       reported {@code RecoveryRequired}, in which case the partial state is exactly what the
 *       preserved evidence is for;</li>
 *   <li><b>V4</b> once CURRENT points at B1 the old bytes must never come back: the tree must
 *       differ from B0 (B1 is genuinely on disk);</li>
 *   <li><b>V5</b> a successful run means B1 is published.</li>
 * </ul>
 *
 * <p>The observed classification for every point is printed as {@code [FAULT-MATRIX]} lines; that
 * table is transcribed into the work order as evidence.
 */
class ApplyFaultMatrixTest {

    /**
     * The interruption points the APPLY transaction actually reaches, in pipeline order.
     *
     * <p>Deliberately not the whole {@link io.kcg.sir.application.internal.state.ApplyHooks}
     * surface: the CREATE- and DELETE-specific hooks are exercised by their own matrix classes.
     */
    private static final List<String> APPLY_HOOKS = List.of(
            "afterTransactionDirCreate",
            "afterJournalCreate",
            "afterStagingWrite",
            "afterBundleStage",
            "afterJournalForce",
            "beforeBackup",
            "afterTargetMoveToBackup",
            "afterBackup",
            "beforeCommitMove",
            "afterStagedMoveToTarget",
            "afterCommitMove",
            "afterAllCommits",
            "afterBundlePublish",
            "beforeCurrentNewWrite",
            "afterCurrentNewWrite",
            "afterCurrentAtomicMove",
            "afterPublishReread",
            "beforeCleanup");

    @TempDir
    Path tempDir;

    static Stream<String> applyHookPoints() {
        return APPLY_HOOKS.stream();
    }

    @ParameterizedTest(name = "APPLY fault at {0}")
    @MethodSource("applyHookPoints")
    void faultAtEachInterruptionPointLeavesADirectionConsistentState(String hook) throws Exception {
        ChangeFaultMatrixSupport.Baseline baseline =
                ChangeFaultMatrixSupport.prepare(tempDir, ChangeFaultMatrixSupport.Family.UPDATE);
        FailingApplyHooks hooks = new FailingApplyHooks(hook);

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);
        System.out.println(ChangeFaultMatrixSupport
                .assertDirectionConsistent("APPLY", hook, baseline, hooks, result).row());
    }

    /**
     * {@code beforeRollbackStep} is a <em>secondary</em> hook: the transaction only calls it while
     * compensating an earlier failure, so no fault can be injected at the point itself. Its
     * coverage therefore belongs to the compensation cases: fail one commit step and assert that
     * the rollback steps were reached and the tree was restored to B0.
     */
    @org.junit.jupiter.api.Test
    void rollbackStepsAreReachedDuringCompensation() throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, ChangeFaultMatrixSupport.Family.UPDATE);
        FailingApplyHooks hooks = new FailingApplyHooks("beforeCommitMove");

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);

        assertTrue(hooks.firedAt("beforeCommitMove"), "the injected point must be reached");
        assertTrue(hooks.firedAt("beforeRollbackStep"),
                "compensating a failed commit must reach the rollback steps; fired hooks="
                        + hooks.firedHooks());
        assertTrue(ChangeFaultMatrixSupport.treeEquals(baseline.b0Bytes(),
                        ChangeFaultMatrixSupport.readTree(baseline.outputRoot())),
                "after a compensated failure the tree must be byte-identical to B0: "
                        + ChangeFaultMatrixSupport.describeTreeDiff(baseline.b0Bytes(),
                                ChangeFaultMatrixSupport.readTree(baseline.outputRoot())));
        assertEquals(baseline.baselineId(),
                ChangeFaultMatrixSupport.readCurrentOrNull(baseline.stateRoot()),
                "a compensated failure must leave CURRENT on B0");
        assertFalse(result instanceof ChangeApplyResult.Applied,
                "a failed commit must not be reported as applied");
    }

    /**
     * A fault during cleanup happens after B1 is published, so the change itself stands and the
     * run is reported as applied — but the fault must be <em>surfaced</em> as a cleanup
     * diagnostic and the transaction evidence must be kept, so cleanup can be completed by
     * recovery instead of being silently dropped.
     */
    @org.junit.jupiter.api.Test
    void cleanupFaultIsSurfacedAndKeepsRecoverableEvidence() throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, ChangeFaultMatrixSupport.Family.UPDATE);
        FailingApplyHooks hooks = new FailingApplyHooks("beforeCleanup");

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);

        assertTrue(hooks.firedAt("beforeCleanup"), "the injected point must be reached");
        assertTrue(result instanceof ChangeApplyResult.Applied,
                "a fault after B1 is published must not undo the change: " + result);
        List<String> codes = result.diagnostics().stream()
                .map(ChangeExecutionDiagnostic::code).toList();
        assertTrue(codes.stream().anyMatch(c -> c.startsWith("SIR-APP-CHANGE-CLEANUP")),
                "a swallowed cleanup fault would hide an unrecovered transaction; diagnostics="
                        + codes);
        Map<String, Integer> evidence =
                ChangeFaultMatrixSupport.transactionEvidence(baseline.stateRoot());
        assertFalse(evidence.isEmpty(),
                "the transaction evidence must be kept so cleanup can be completed by recovery");
    }

    /** Guards the hook list against silently shrinking the matrix. */
    @org.junit.jupiter.api.Test
    void applyHookMatrixCoversEveryReachablePointInPipelineOrder() {
        assertEquals(18, APPLY_HOOKS.size(),
                "the APPLY hook list changed; update this assertion and the work-order table "
                        + "together so the matrix cannot silently shrink");
    }
}
