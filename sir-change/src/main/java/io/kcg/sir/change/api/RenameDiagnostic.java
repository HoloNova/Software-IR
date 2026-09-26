package io.kcg.sir.change.api;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

public record RenameDiagnostic(
   String code,
   RenameDiagnosticStage stage,
   ChangeDiagnosticSeverity severity,
   String message,
   Optional<SymbolId> declarationSymbol,
   Optional<String> relativePath
) {
   public RenameDiagnostic {
      if (code == null || code.isBlank()) {
         throw new IllegalArgumentException("code must not be blank");
      }

      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(severity, "severity");
      if (message == null || message.isBlank()) {
         throw new IllegalArgumentException("message must not be blank");
      }

      declarationSymbol = Objects.requireNonNull(declarationSymbol, "declarationSymbol");
      relativePath = Objects.requireNonNull(relativePath, "relativePath");
   }

   public static RenameDiagnostic error(
      String code, RenameDiagnosticStage stage, SymbolId declarationSymbol, String message
   ) {
      return new RenameDiagnostic(
         code, stage, ChangeDiagnosticSeverity.ERROR, message, Optional.ofNullable(declarationSymbol), Optional.empty()
      );
   }

   public static RenameDiagnostic errorForPath(
      String code, RenameDiagnosticStage stage, SymbolId declarationSymbol, String relativePath, String message
   ) {
      return new RenameDiagnostic(
         code,
         stage,
         ChangeDiagnosticSeverity.ERROR,
         message,
         Optional.ofNullable(declarationSymbol),
         Optional.ofNullable(relativePath)
      );
   }

   public boolean isError() {
      return this.severity.isError();
   }
}
