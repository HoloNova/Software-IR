package io.kcg.sir.application.internal;

import java.nio.file.Path;

interface TransactionHooks {
   default void beforeStagingWrite(Path stagingDir) throws Exception {
   }

   default void beforeCommitFile(int index, Path stagedPath, Path target) throws Exception {
   }

   default void afterBackupBeforeCommit(int index, Path backup, Path target) throws Exception {
   }

   default void beforeRollbackStep(int stepIndex, String description) throws Exception {
   }

   static TransactionHooks noOp() {
      return new TransactionHooks() {};
   }
}
