package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface ChangeRecoveryResult
   permits ChangeRecoveryResult.Recovered,
   ChangeRecoveryResult.RolledBack,
   ChangeRecoveryResult.RecoveryRequired,
   ChangeRecoveryResult.Failure {
   List<ChangeExecutionDiagnostic> diagnostics();

   static ChangeRecoveryResult recovered(ChangeBaselineReceipt currentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeRecoveryResult.Recovered(currentBaselineReceipt, diagnostics);
   }

   static ChangeRecoveryResult rolledBack(ChangeBaselineReceipt currentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeRecoveryResult.RolledBack(currentBaselineReceipt, diagnostics);
   }

   static ChangeRecoveryResult recoveryRequired(
      RecoveryHandle handle, Optional<ChangeBaselineReceipt> knownCurrentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeRecoveryResult.RecoveryRequired(handle, knownCurrentBaselineReceipt, diagnostics);
   }

   static ChangeRecoveryResult failure(ChangeExecutionStage failedStage, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeRecoveryResult.Failure(failedStage, diagnostics);
   }

   record Failure(ChangeExecutionStage failedStage, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeRecoveryResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Recovered(ChangeBaselineReceipt currentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeRecoveryResult {
      public Recovered {
         Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RecoveryRequired(RecoveryHandle handle, Optional<ChangeBaselineReceipt> knownCurrentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics)
      implements ChangeRecoveryResult {
      public RecoveryRequired {
         Objects.requireNonNull(handle, "handle");
         knownCurrentBaselineReceipt = Objects.requireNonNull(knownCurrentBaselineReceipt, "knownCurrentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RolledBack(ChangeBaselineReceipt currentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeRecoveryResult {
      public RolledBack {
         Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
