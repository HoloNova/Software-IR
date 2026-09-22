package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.bundle.BaselineBundle;
import io.kcg.sir.application.internal.state.ApplyHooks;
import io.kcg.sir.application.internal.state.CreateTransactionJournal;
import io.kcg.sir.application.internal.state.DeleteTransactionJournal;
import io.kcg.sir.application.internal.state.TransactionJournal;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An ApplyHooks that records every hook it reaches and fails at exactly one of them.
 *
 * <p>Two properties make the fault matrix meaningful:
 *
 * <ul>
 *   <li><b>Fired tracking</b>: a case whose target hook was never reached is a vacuous case, so
 *       each test asserts the hook fired. Without this, a renamed or unreachable hook would look
 *       like a passing "fault handled correctly" test.</li>
 *   <li><b>Single point</b>: only the named hook fails, so the observed terminal state can be
 *       attributed to that one interruption point.</li>
 * </ul>
 *
 * <p>Every method of the interface is overridden so that no hook can silently fall through to the
 * no-op default. Adding a hook to the interface without adding it here is therefore a compile
 * error, which keeps this matrix in step with the production hook surface.
 */
final class FailingApplyHooks implements ApplyHooks {

    private final String targetHook;
    private final Set<String> fired = new LinkedHashSet<>();

    FailingApplyHooks(String targetHook) {
        this.targetHook = java.util.Objects.requireNonNull(targetHook, "targetHook");
    }

    /** @return the hooks this run actually reached, in call order */
    List<String> firedHooks() {
        return List.copyOf(fired);
    }

    boolean firedAt(String hook) {
        return fired.contains(hook);
    }

    private void check(String hook) throws Exception {
        fired.add(hook);
        if (targetHook.equals(hook)) {
            throw new IllegalStateException("INJECTED_FAULT_AT:" + hook);
        }
    }

    @Override
    public void afterStagingWrite(Path stagingDir) throws Exception {
        check("afterStagingWrite");
    }

    @Override
    public void afterBundleStage(BaselineBundle b1) throws Exception {
        check("afterBundleStage");
    }

    @Override
    public void afterTransactionDirCreate(Path transactionDir) throws Exception {
        check("afterTransactionDirCreate");
    }

    @Override
    public void afterJournalCreate(TransactionJournal journal) throws Exception {
        check("afterJournalCreate");
    }

    @Override
    public void afterJournalForce(TransactionJournal journal, String transition) throws Exception {
        check("afterJournalForce");
    }

    @Override
    public void afterJournalCreate(CreateTransactionJournal journal) throws Exception {
        check("afterJournalCreate");
    }

    @Override
    public void afterJournalForce(CreateTransactionJournal journal, String transition) throws Exception {
        check("afterJournalForce");
    }

    @Override
    public void afterCreateDirectoryPlanComputed(CreateTransactionJournal journal, List<String> plannedDirectories) throws Exception {
        check("afterCreateDirectoryPlanComputed");
    }

    @Override
    public void beforeCreateDirectoryIntent(int index, String relPath) throws Exception {
        check("beforeCreateDirectoryIntent");
    }

    @Override
    public void afterCreateDirectoryCreate(int index, String relPath, Path createdDir) throws Exception {
        check("afterCreateDirectoryCreate");
    }

    @Override
    public void afterCreateDirectoryComplete(int index, String relPath) throws Exception {
        check("afterCreateDirectoryComplete");
    }

    @Override
    public void beforeCreateFileIntent(int index, String relPath, Path staged, Path target) throws Exception {
        check("beforeCreateFileIntent");
    }

    @Override
    public void afterCreateLink(int index, String relPath, Path target) throws Exception {
        check("afterCreateLink");
    }

    @Override
    public void afterCreateFileComplete(int index, String relPath) throws Exception {
        check("afterCreateFileComplete");
    }

    @Override
    public void afterAllCreateLinks() throws Exception {
        check("afterAllCreateLinks");
    }

    @Override
    public void beforeCreateRollbackDeleteIntent(int index, String relPath) throws Exception {
        check("beforeCreateRollbackDeleteIntent");
    }

    @Override
    public void afterCreateRollbackDelete(int index, String relPath) throws Exception {
        check("afterCreateRollbackDelete");
    }

    @Override
    public void afterCreateRollbackComplete(int index, String relPath) throws Exception {
        check("afterCreateRollbackComplete");
    }

    @Override
    public void beforeBackup(int index, Path target) throws Exception {
        check("beforeBackup");
    }

    @Override
    public void afterTargetMoveToBackup(int index, Path backup) throws Exception {
        check("afterTargetMoveToBackup");
    }

    @Override
    public void afterBackup(int index, Path backup) throws Exception {
        check("afterBackup");
    }

    @Override
    public void beforeCommitMove(int index, Path staged, Path target) throws Exception {
        check("beforeCommitMove");
    }

    @Override
    public void afterStagedMoveToTarget(int index, Path target) throws Exception {
        check("afterStagedMoveToTarget");
    }

    @Override
    public void afterCommitMove(int index, Path target) throws Exception {
        check("afterCommitMove");
    }

    @Override
    public void afterAllCommits() throws Exception {
        check("afterAllCommits");
    }

    @Override
    public void afterBundlePublish() throws Exception {
        check("afterBundlePublish");
    }

    @Override
    public void beforeCurrentNewWrite() throws Exception {
        check("beforeCurrentNewWrite");
    }

    @Override
    public void afterCurrentNewWrite() throws Exception {
        check("afterCurrentNewWrite");
    }

    @Override
    public void afterCurrentAtomicMove() throws Exception {
        check("afterCurrentAtomicMove");
    }

    @Override
    public void afterPublishReread() throws Exception {
        check("afterPublishReread");
    }

    @Override
    public void beforeRollbackStep(int stepIndex, String description) throws Exception {
        check("beforeRollbackStep");
    }

    @Override
    public void beforeCleanup() throws Exception {
        check("beforeCleanup");
    }

    @Override
    public void afterJournalCreate(DeleteTransactionJournal journal) throws Exception {
        check("afterJournalCreate");
    }

    @Override
    public void afterJournalForce(DeleteTransactionJournal journal, String transition) throws Exception {
        check("afterJournalForce");
    }

    @Override
    public void beforeBackupLinkIntent(int index, String relPath, Path target, Path backup) throws Exception {
        check("beforeBackupLinkIntent");
    }

    @Override
    public void afterBackupLinkCreate(int index, String relPath, Path backup) throws Exception {
        check("afterBackupLinkCreate");
    }

    @Override
    public void beforeDeleteIntent(int index, String relPath, Path target) throws Exception {
        check("beforeDeleteIntent");
    }

    @Override
    public void afterTargetDelete(int index, String relPath) throws Exception {
        check("afterTargetDelete");
    }

    @Override
    public void afterDeleteFileComplete(int index, String relPath) throws Exception {
        check("afterDeleteFileComplete");
    }

    @Override
    public void afterAllDeleteLinks() throws Exception {
        check("afterAllDeleteLinks");
    }

    @Override
    public void beforeDeleteRollbackStep(int index, String relPath, DeleteTransactionJournal.FileTransitionState fileState) throws Exception {
        check("beforeDeleteRollbackStep");
    }

    @Override
    public void afterBackupDelete(int index, String relPath) throws Exception {
        check("afterBackupDelete");
    }

    @Override
    public void afterRestoreLinkCreate(int index, String relPath, Path target) throws Exception {
        check("afterRestoreLinkCreate");
    }

    @Override
    public void afterDeleteRollbackComplete(int index, String relPath) throws Exception {
        check("afterDeleteRollbackComplete");
    }
}
