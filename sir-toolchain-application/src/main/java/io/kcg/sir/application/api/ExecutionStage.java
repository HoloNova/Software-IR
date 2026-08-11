package io.kcg.sir.application.api;

public enum ExecutionStage {
   READ,
   PARSE,
   SEMANTIC,
   LOWERING,
   GENERATION,
   PREFLIGHT,
   GRAPH,
   WRITE,
   ROLLBACK;
}
