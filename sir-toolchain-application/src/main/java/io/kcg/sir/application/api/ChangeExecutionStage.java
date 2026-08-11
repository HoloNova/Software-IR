package io.kcg.sir.application.api;

public enum ChangeExecutionStage {
   READ,
   BASELINE,
   RECOMPILE,
   PLAN,
   PROTECT,
   COMMIT,
   BASELINE_PUBLISH,
   RECOVERY,
   CLEANUP;
}
