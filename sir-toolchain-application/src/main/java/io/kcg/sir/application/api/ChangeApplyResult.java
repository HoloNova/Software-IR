package io.kcg.sir.application.api;

import io.kcg.sir.change.api.NoChangeReason;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface ChangeApplyResult
   permits ChangeApplyResult.Applied,
   ChangeApplyResult.NoChanges,
   ChangeApplyResult.Failure,
   ChangeApplyResult.RecoveryRequired {
   List<ChangeExecutionDiagnostic> diagnostics();

   static ChangeApplyResult applied(
      ChangeApplyOutcome outcome, ChangeBaselineReceipt newBaselineReceipt, ChangeOutputManifest outputManifest, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeApplyResult.Applied(outcome, newBaselineReceipt, outputManifest, diagnostics);
   }

   static ChangeApplyResult noChanges(ChangeBaselineReceipt currentBaselineReceipt, NoChangeReason reason, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeApplyResult.NoChanges(currentBaselineReceipt, reason, diagnostics);
   }

   static ChangeApplyResult failure(
      ChangeExecutionStage failedStage,
      ChangeApplyDisposition disposition,
      Optional<ChangeBaselineReceipt> effectiveBaselineReceipt,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeApplyResult.Failure(failedStage, disposition, effectiveBaselineReceipt, diagnostics);
   }

   static ChangeApplyResult recoveryRequired(
      ChangeExecutionStage failedStage,
      RecoveryHandle handle,
      Optional<ChangeBaselineReceipt> knownCurrentBaselineReceipt,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeApplyResult.RecoveryRequired(failedStage, handle, knownCurrentBaselineReceipt, diagnostics);
   }

   record Applied(
      ChangeApplyOutcome outcome, ChangeBaselineReceipt newBaselineReceipt, ChangeOutputManifest outputManifest, List<ChangeExecutionDiagnostic> diagnostics
   ) implements ChangeApplyResult {
      public Applied {
         Objects.requireNonNull(outcome, "outcome");
         Objects.requireNonNull(newBaselineReceipt, "newBaselineReceipt");
         Objects.requireNonNull(outputManifest, "outputManifest");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Failure(
      ChangeExecutionStage failedStage,
      ChangeApplyDisposition disposition,
      Optional<ChangeBaselineReceipt> effectiveBaselineReceipt,
      List<ChangeExecutionDiagnostic> diagnostics
   ) implements ChangeApplyResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         Objects.requireNonNull(disposition, "disposition");
         effectiveBaselineReceipt = Objects.requireNonNull(effectiveBaselineReceipt, "effectiveBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record NoChanges(ChangeBaselineReceipt currentBaselineReceipt, NoChangeReason reason, List<ChangeExecutionDiagnostic> diagnostics)
      implements ChangeApplyResult {
      public NoChanges {
         Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         Objects.requireNonNull(reason, "reason");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RecoveryRequired(
      ChangeExecutionStage failedStage,
      RecoveryHandle handle,
      Optional<ChangeBaselineReceipt> knownCurrentBaselineReceipt,
      List<ChangeExecutionDiagnostic> diagnostics
   ) implements ChangeApplyResult {
      public RecoveryRequired {
         Objects.requireNonNull(failedStage, "failedStage");
         Objects.requireNonNull(handle, "handle");
         knownCurrentBaselineReceipt = Objects.requireNonNull(knownCurrentBaselineReceipt, "knownCurrentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
