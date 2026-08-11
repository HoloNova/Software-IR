package io.kcg.sir.application.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

public record ExecutionDiagnostic(
   String code,
   ExecutionStage stage,
   ExecutionSeverity severity,
   String message,
   Optional<SourceSpan> sourceSpan,
   Optional<LoweredNodeId> loweredNodeId,
   Optional<String> relativePath
) {
   public ExecutionDiagnostic {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(message, "message");
      sourceSpan = Objects.requireNonNull(sourceSpan, "sourceSpan");
      loweredNodeId = Objects.requireNonNull(loweredNodeId, "loweredNodeId");
      relativePath = Objects.requireNonNull(relativePath, "relativePath");
      if (code.isBlank()) {
         throw new IllegalArgumentException("code must not be blank");
      }

      if (message.isBlank()) {
         throw new IllegalArgumentException("message must not be blank");
      }
   }

   public static ExecutionDiagnostic error(String code, ExecutionStage stage, String message) {
      return new ExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.empty(), Optional.empty(), Optional.empty());
   }

   public static ExecutionDiagnostic warning(String code, ExecutionStage stage, String message) {
      return new ExecutionDiagnostic(code, stage, ExecutionSeverity.WARNING, message, Optional.empty(), Optional.empty(), Optional.empty());
   }

   public boolean isError() {
      return this.severity.isError();
   }
}
