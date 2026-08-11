package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

public sealed interface ChangePlanningContextResult
   permits ChangePlanningContextResult.Success,
   ChangePlanningContextResult.Failure,
   ChangePlanningContextResult.RecoveryRequired {
   List<ChangeExecutionDiagnostic> diagnostics();

   static ChangePlanningContextResult.Success success(ChangePlanningContext context, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangePlanningContextResult.Success(context, diagnostics);
   }

   static ChangePlanningContextResult.Failure failure(ChangeExecutionStage failedStage, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangePlanningContextResult.Failure(failedStage, diagnostics);
   }

   static ChangePlanningContextResult.RecoveryRequired recoveryRequired(RecoveryHandle handle, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangePlanningContextResult.RecoveryRequired(handle, diagnostics);
   }

   record Failure(ChangeExecutionStage failedStage, List<ChangeExecutionDiagnostic> diagnostics) implements ChangePlanningContextResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RecoveryRequired(RecoveryHandle handle, List<ChangeExecutionDiagnostic> diagnostics) implements ChangePlanningContextResult {
      public RecoveryRequired {
         Objects.requireNonNull(handle, "handle");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Success(ChangePlanningContext context, List<ChangeExecutionDiagnostic> diagnostics) implements ChangePlanningContextResult {
      public Success {
         Objects.requireNonNull(context, "context");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
