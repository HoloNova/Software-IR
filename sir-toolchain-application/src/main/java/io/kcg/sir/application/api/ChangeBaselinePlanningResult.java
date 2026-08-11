package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface ChangeBaselinePlanningResult
   permits ChangeBaselinePlanningResult.Success,
   ChangeBaselinePlanningResult.Failure,
   ChangeBaselinePlanningResult.RecoveryRequired {
   List<ChangeExecutionDiagnostic> diagnostics();

   static ChangeBaselinePlanningResult success(
      ChangeBaselineReceipt currentBaselineReceipt,
      ChangeAnalysis analysis,
      ProjectGraph baseGraph,
      ProjectGraph candidateGraph,
      List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeBaselinePlanningResult.Success(currentBaselineReceipt, analysis, baseGraph, candidateGraph, diagnostics);
   }

   static ChangeBaselinePlanningResult failure(
      ChangeExecutionStage failedStage, Optional<ChangeBaselineReceipt> currentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics
   ) {
      return new ChangeBaselinePlanningResult.Failure(failedStage, currentBaselineReceipt, diagnostics);
   }

   static ChangeBaselinePlanningResult recoveryRequired(RecoveryHandle handle, List<ChangeExecutionDiagnostic> diagnostics) {
      return new ChangeBaselinePlanningResult.RecoveryRequired(handle, diagnostics);
   }

   record Failure(ChangeExecutionStage failedStage, Optional<ChangeBaselineReceipt> currentBaselineReceipt, List<ChangeExecutionDiagnostic> diagnostics)
      implements ChangeBaselinePlanningResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         currentBaselineReceipt = Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record RecoveryRequired(RecoveryHandle handle, List<ChangeExecutionDiagnostic> diagnostics) implements ChangeBaselinePlanningResult {
      public RecoveryRequired {
         Objects.requireNonNull(handle, "handle");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }

   record Success(
      ChangeBaselineReceipt currentBaselineReceipt,
      ChangeAnalysis analysis,
      ProjectGraph baseGraph,
      ProjectGraph candidateGraph,
      List<ChangeExecutionDiagnostic> diagnostics
   ) implements ChangeBaselinePlanningResult {
      public Success {
         Objects.requireNonNull(currentBaselineReceipt, "currentBaselineReceipt");
         Objects.requireNonNull(analysis, "analysis");
         Objects.requireNonNull(baseGraph, "baseGraph");
         Objects.requireNonNull(candidateGraph, "candidateGraph");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
   }
}
