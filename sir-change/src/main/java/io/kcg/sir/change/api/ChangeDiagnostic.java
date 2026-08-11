package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

public record ChangeDiagnostic(
   String code,
   ChangeDiagnosticStage stage,
   ChangeDiagnosticSeverity severity,
   String message,
   int operationIndex,
   Optional<SourceSpan> sourceSpan,
   Optional<SymbolId> targetSymbol,
   Optional<LoweredNodeId> artifactId,
   Optional<String> relativePath
) {
   public ChangeDiagnostic {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(message, "message");
      sourceSpan = Objects.requireNonNull(sourceSpan, "sourceSpan");
      targetSymbol = Objects.requireNonNull(targetSymbol, "targetSymbol");
      artifactId = Objects.requireNonNull(artifactId, "artifactId");
      relativePath = Objects.requireNonNull(relativePath, "relativePath");
      if (code.isBlank()) {
         throw new IllegalArgumentException("code must not be blank");
      }

      if (message.isBlank()) {
         throw new IllegalArgumentException("message must not be blank");
      }

      if (operationIndex < 0) {
         throw new IllegalArgumentException("operationIndex must not be negative");
      }
   }

   public static ChangeDiagnostic error(String code, ChangeDiagnosticStage stage, int operationIndex, String message) {
      return new ChangeDiagnostic(
         code, stage, ChangeDiagnosticSeverity.ERROR, message, operationIndex, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
      );
   }

   public boolean isError() {
      return this.severity.isError();
   }
}
