package io.kcg.sir.change.api;

public enum ChangeDiagnosticSeverity {
   ERROR,
   WARNING,
   INFO;

   public boolean isError() {
      return this == ERROR;
   }
}
