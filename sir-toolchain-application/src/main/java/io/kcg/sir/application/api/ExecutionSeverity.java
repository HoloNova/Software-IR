package io.kcg.sir.application.api;

public enum ExecutionSeverity {
   ERROR,
   WARNING,
   INFO;

   public boolean isError() {
      return this == ERROR;
   }
}
