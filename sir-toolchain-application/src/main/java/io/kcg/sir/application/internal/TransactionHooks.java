package io.kcg.sir.application.internal;

import java.nio.file.Path;

/** Test seam: lets a test make the staging step fail or change the world between staging and publication. */
interface TransactionHooks {
   default void beforeStagingWrite(Path stagingDir) throws Exception {
   }

   static TransactionHooks noOp() {
      return new TransactionHooks() {};
   }
}
