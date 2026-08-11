package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

public sealed interface ChangeBaselineRegistrationResult
   permits ChangeBaselineRegistrationResult.Success,
   ChangeBaselineRegistrationResult.AlreadyRegistered,
   ChangeBaselineRegistrationResult.Failure,
   ChangeBaselineRegistrationResult.RecoveryRequired {
   List<ChangeExecutionDiagnostic> diagnostics();

   static ChangeBaselineRegistrationResult success(ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeBaselineRegistrationResult.Success(receipt, diagnostics);
   }

   static ChangeBaselineRegistrationResult alreadyRegistered(ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeBaselineRegistrationResult.AlreadyRegistered(receipt, diagnostics);
   }

   static ChangeBaselineRegistrationResult failure(ChangeExecutionStage failedStage, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeBaselineRegistrationResult.Failure(failedStage, diagnostics);
   }

   static ChangeBaselineRegistrationResult recoveryRequired(RecoveryHandle handle, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeBaselineRegistrationResult.RecoveryRequired(handle, diagnostics);
   }

   record AlreadyRegistered(ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeBaselineRegistrationResult {
      public AlreadyRegistered {
         Objects.requireNonNull(receipt, "receipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Failure(ChangeExecutionStage failedStage, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeBaselineRegistrationResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RecoveryRequired(RecoveryHandle handle, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeBaselineRegistrationResult {
      public RecoveryRequired {
         Objects.requireNonNull(handle, "handle");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Success(ChangeBaselineReceipt receipt, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeBaselineRegistrationResult {
      public Success {
         Objects.requireNonNull(receipt, "receipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
