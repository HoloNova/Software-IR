package io.kcg.sir.application.api;

import io.kcg.sir.change.api.RenameNoChangeReason;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Outcome of the single explicit rename application entry point. */
public sealed interface RenameApplyResult permits RenameApplyResult.Applied, RenameApplyResult.NoChanges, RenameApplyResult.Failure, RenameApplyResult.RecoveryRequired {
   List<ChangeExecutionDiagnostic> diagnostics();

   record Applied(
      ChangeBaselineReceipt baseBaselineReceipt,
      ChangeBaselineReceipt newBaselineReceipt,
      List<String> appliedFiles,
      boolean transactionCleaned,
      List<ChangeExecutionDiagnostic> diagnostics
   ) implements RenameApplyResult {
      public Applied {
         Objects.requireNonNull(baseBaselineReceipt, "baseBaselineReceipt");
         Objects.requireNonNull(newBaselineReceipt, "newBaselineReceipt");
         appliedFiles = List.copyOf(Objects.requireNonNull(appliedFiles, "appliedFiles"));
         if (!transactionCleaned) throw new IllegalArgumentException("successful rename transaction must be cleaned");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record NoChanges(ChangeBaselineReceipt currentBaselineReceipt, RenameNoChangeReason reason, List<ChangeExecutionDiagnostic> diagnostics)
      implements RenameApplyResult {
      public NoChanges {
         Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         Objects.requireNonNull(reason, "reason");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Failure(ChangeExecutionStage failedStage, Optional<ChangeBaselineReceipt> currentBaselineReceipt,
                  List<ChangeExecutionDiagnostic> diagnostics) implements RenameApplyResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         currentBaselineReceipt = Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RecoveryRequired(RecoveryHandle handle, Optional<ChangeBaselineReceipt> currentBaselineReceipt,
                           List<ChangeExecutionDiagnostic> diagnostics) implements RenameApplyResult {
      public RecoveryRequired {
         Objects.requireNonNull(handle, "handle");
         currentBaselineReceipt = Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
