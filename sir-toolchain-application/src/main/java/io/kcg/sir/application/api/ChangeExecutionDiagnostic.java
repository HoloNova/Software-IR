package io.kcg.sir.application.api;

import java.util.Objects;
import java.util.Optional;

public record ChangeExecutionDiagnostic(String code, ChangeExecutionStage stage, ExecutionSeverity severity, String message, Optional<String> relativePath) {
   public ChangeExecutionDiagnostic {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(message, "message");
      relativePath = Objects.requireNonNull(relativePath, "relativePath");
      if (code.isBlank()) {
         throw new IllegalArgumentException("code must not be blank");
      }

      if (message.isBlank()) {
         throw new IllegalArgumentException("message must not be blank");
      }
   }

   public static ChangeExecutionDiagnostic error(String code, ChangeExecutionStage stage, String message) {
      return new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   public static ChangeExecutionDiagnostic error(String code, ChangeExecutionStage stage, String message, String relativePath) {
      return new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.of(relativePath));
   }

   public static ChangeExecutionDiagnostic warning(String code, ChangeExecutionStage stage, String message) {
      return new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.WARNING, message, Optional.empty());
   }

   public static ChangeExecutionDiagnostic info(String code, ChangeExecutionStage stage, String message) {
      return new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.INFO, message, Optional.empty());
   }

   public boolean isError() {
      return this.severity.isError();
   }
}
