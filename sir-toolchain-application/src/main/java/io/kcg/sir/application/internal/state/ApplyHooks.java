package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.internal.bundle.BaselineBundle;
import java.nio.file.Path;
import java.util.List;

public interface ApplyHooks {
   default void afterStagingWrite(Path stagingDir) throws Exception {
   }

   default void afterBundleStage(BaselineBundle b1) throws Exception {
   }

   default void afterTransactionDirCreate(Path transactionDir) throws Exception {
   }

   default void afterJournalCreate(TransactionJournal journal) throws Exception {
   }

   default void afterJournalForce(TransactionJournal journal, String transition) throws Exception {
   }

   default void afterJournalCreate(CreateTransactionJournal journal) throws Exception {
   }

   default void afterJournalForce(CreateTransactionJournal journal, String transition) throws Exception {
   }

   default void afterCreateDirectoryPlanComputed(CreateTransactionJournal journal, List<String> plannedDirectories) throws Exception {
   }

   default void beforeCreateDirectoryIntent(int index, String relPath) throws Exception {
   }

   default void afterCreateDirectoryCreate(int index, String relPath, Path createdDir) throws Exception {
   }

   default void afterCreateDirectoryComplete(int index, String relPath) throws Exception {
   }

   default void beforeCreateFileIntent(int index, String relPath, Path staged, Path target) throws Exception {
   }

   default void afterCreateLink(int index, String relPath, Path target) throws Exception {
   }

   default void afterCreateFileComplete(int index, String relPath) throws Exception {
   }

   default void afterAllCreateLinks() throws Exception {
   }

   default void beforeCreateRollbackDeleteIntent(int index, String relPath) throws Exception {
   }

   default void afterCreateRollbackDelete(int index, String relPath) throws Exception {
   }

   default void afterCreateRollbackComplete(int index, String relPath) throws Exception {
   }

   default void beforeBackup(int index, Path target) throws Exception {
   }

   default void afterTargetMoveToBackup(int index, Path backup) throws Exception {
   }

   default void afterBackup(int index, Path backup) throws Exception {
   }

   default void beforeCommitMove(int index, Path staged, Path target) throws Exception {
   }

   default void afterStagedMoveToTarget(int index, Path target) throws Exception {
   }

   default void afterCommitMove(int index, Path target) throws Exception {
   }

   default void afterAllCommits() throws Exception {
   }

   default void afterBundlePublish() throws Exception {
   }

   default void beforeCurrentNewWrite() throws Exception {
   }

   default void afterCurrentNewWrite() throws Exception {
   }

   default void afterCurrentAtomicMove() throws Exception {
   }

   default void afterPublishReread() throws Exception {
   }

   default void beforeRollbackStep(int stepIndex, String description) throws Exception {
   }

   default void beforeCleanup() throws Exception {
   }

   default void afterJournalCreate(DeleteTransactionJournal journal) throws Exception {
   }

   default void afterJournalForce(DeleteTransactionJournal journal, String transition) throws Exception {
   }

   default void beforeBackupLinkIntent(int index, String relPath, Path target, Path backup) throws Exception {
   }

   default void afterBackupLinkCreate(int index, String relPath, Path backup) throws Exception {
   }

   default void beforeDeleteIntent(int index, String relPath, Path target) throws Exception {
   }

   default void afterTargetDelete(int index, String relPath) throws Exception {
   }

   default void afterDeleteFileComplete(int index, String relPath) throws Exception {
   }

   default void afterAllDeleteLinks() throws Exception {
   }

   default void beforeDeleteRollbackStep(int index, String relPath, DeleteTransactionJournal.FileTransitionState fileState) throws Exception {
   }

   default void afterBackupDelete(int index, String relPath) throws Exception {
   }

   default void afterRestoreLinkCreate(int index, String relPath, Path target) throws Exception {
   }

   default void afterDeleteRollbackComplete(int index, String relPath) throws Exception {
   }

   static ApplyHooks noOp() {
      return new ApplyHooks() {};
   }
}
