package io.kcg.sir.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Baseline;
import io.kcg.sir.application.api.ChangeFaultMatrixSupport.Family;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fault matrix for the CREATE transaction (a change that adds a declaration).
 *
 * <p>Same contract as the APPLY matrix: one injected failure per interruption point, then the
 * direction invariants in
 * {@link ChangeFaultMatrixSupport#assertDirectionConsistent}. The hook list is the set of points
 * the create transaction actually calls, taken from its call sites; the rollback-only points are
 * covered separately because they can only be reached while compensating an earlier failure.
 */
class CreateFaultMatrixTest {

    /**
     * Interruption points the CREATE transaction reaches on its forward path.
     *
     * <p>Excludes the rollback-only points ({@code beforeCreateRollbackDeleteIntent},
     * {@code afterCreateRollbackDelete}, {@code afterCreateRollbackComplete}) for the same reason
     * the APPLY matrix excludes {@code beforeRollbackStep}: injecting a fault at a point that only
     * runs during compensation would be a vacuous case.
     */
    private static final List<String> CREATE_HOOKS = List.of(
            "afterTransactionDirCreate",
            "afterJournalCreate",
            "afterStagingWrite",
            "afterBundleStage",
            "afterJournalForce",
            "beforeCreateFileIntent",
            "afterCreateLink",
            "afterCreateFileComplete",
            "afterAllCreateLinks",
            "afterBundlePublish",
            "beforeCurrentNewWrite",
            "afterCurrentNewWrite",
            "afterCurrentAtomicMove",
            "afterPublishReread",
            "beforeCleanup");

    /**
     * Interruption points that only exist when the create has to make a directory.
     *
     * <p>They are kept apart because reachability is input-dependent: the default CREATE fixture
     * pair already generates every directory its new files need, so its directory plan is empty and
     * these hooks are never called. The fixture pair below deliberately has no capability in the
     * base, so the added capability's files need directories that do not exist yet.
     */
    private static final List<String> CREATE_DIRECTORY_HOOKS = List.of(
            "afterCreateDirectoryPlanComputed",
            "beforeCreateDirectoryIntent",
            "afterCreateDirectoryCreate",
            "afterCreateDirectoryComplete");

    /** Fixtures that make the create plan a real directory list. */
    private static final String DIRECTORY_BASE = "/valid/fault-matrix-dirs-base.sir";
    private static final String DIRECTORY_CANDIDATE = "/valid/fault-matrix-dirs-candidate.sir";

    /** Points that only run while a CREATE transaction compensates an earlier failure. */
    private static final List<String> CREATE_ROLLBACK_ONLY = List.of(
            "beforeCreateRollbackDeleteIntent",
            "afterCreateRollbackDelete",
            "afterCreateRollbackComplete");

    @TempDir
    Path tempDir;

    static Stream<String> createHookPoints() {
        return CREATE_HOOKS.stream();
    }

    @ParameterizedTest(name = "CREATE fault at {0}")
    @MethodSource("createHookPoints")
    void faultAtEachInterruptionPointLeavesADirectionConsistentState(String hook) throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.CREATE);
        FailingApplyHooks hooks = new FailingApplyHooks(hook);

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);
        System.out.println(ChangeFaultMatrixSupport
                .assertDirectionConsistent("CREATE", hook, baseline, hooks, result).row());
    }

    static Stream<String> createDirectoryHookPoints() {
        return CREATE_DIRECTORY_HOOKS.stream();
    }

    /**
     * The directory points are exercised with fixtures whose base has no capability at all, so the
     * added capability's files need directories that do not exist yet.
     */
    @ParameterizedTest(name = "CREATE directory fault at {0}")
    @MethodSource("createDirectoryHookPoints")
    void faultAtEachDirectoryPointLeavesADirectionConsistentState(String hook) throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.CREATE,
                DIRECTORY_BASE, DIRECTORY_CANDIDATE);
        FailingApplyHooks hooks = new FailingApplyHooks(hook);

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);
        System.out.println(ChangeFaultMatrixSupport
                .assertDirectionConsistent("CREATE-DIRS", hook, baseline, hooks, result).row());
    }

    /**
     * The rollback-only points are reached when an earlier CREATE step fails and the transaction
     * compensates: fail after every file link exists, so compensation has files to remove, and
     * assert that every rollback point ran, that the tree is byte-identical to B0 again, and that
     * no new declaration survived.
     *
     * <p>The injection point matters: failing before the first link leaves nothing to roll back,
     * so the file-removal rollback hooks are never reached. That is why this test injects after
     * {@code afterAllCreateLinks} rather than at the start of the file phase.
     */
    @Test
    void rollbackOnlyPointsAreReachedDuringCompensation() throws Exception {
        Baseline baseline = ChangeFaultMatrixSupport.prepare(tempDir, Family.CREATE);
        FailingApplyHooks hooks = new FailingApplyHooks("afterAllCreateLinks");

        ChangeApplyResult result = ChangeFaultMatrixSupport.apply(baseline, hooks);

        assertTrue(hooks.firedAt("afterAllCreateLinks"), "the injected point must be reached");
        for (String hook : CREATE_ROLLBACK_ONLY) {
            assertTrue(hooks.firedAt(hook),
                    "compensating a failed create must reach " + hook + "; fired="
                            + hooks.firedHooks());
        }
        Map<String, byte[]> tree = ChangeFaultMatrixSupport.readTree(baseline.outputRoot());
        assertTrue(ChangeFaultMatrixSupport.treeEquals(baseline.b0Bytes(), tree),
                "after compensation the tree must be byte-identical to B0: "
                        + ChangeFaultMatrixSupport.describeTreeDiff(baseline.b0Bytes(), tree));
        assertEquals(baseline.baselineId(),
                ChangeFaultMatrixSupport.readCurrentOrNull(baseline.stateRoot()),
                "a compensated create must leave CURRENT on B0");
        assertFalse(result instanceof ChangeApplyResult.Applied,
                "a failed create must not be reported as applied");
    }

    /** Guards the hook list against silently shrinking the matrix. */
    @Test
    void createHookMatrixCoversTheReachableForwardPath() {
        assertEquals(15, CREATE_HOOKS.size(),
                "the CREATE hook list changed; update this assertion and the work-order table "
                        + "together so the matrix cannot silently shrink");
        assertEquals(4, CREATE_DIRECTORY_HOOKS.size(),
                "the CREATE directory-point list changed; update the directory fixture pair with it");
        assertEquals(3, CREATE_ROLLBACK_ONLY.size(),
                "the CREATE rollback-only list changed; update the compensation test with it");
    }
}
