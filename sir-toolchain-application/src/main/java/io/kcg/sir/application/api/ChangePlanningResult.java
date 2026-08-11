package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import java.util.List;
import java.util.Objects;

public sealed interface ChangePlanningResult permits ChangePlanningResult.Success, ChangePlanningResult.Failure {
   List<ExecutionDiagnostic> diagnostics();

   record Failure(ChangePlanningStage failedStage, List<ExecutionDiagnostic> diagnostics) implements ChangePlanningResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
            throw new IllegalArgumentException("Failure must contain at least one ERROR diagnostic");
         }
      }
   }

   record Success(ChangeAnalysis analysis, ProjectGraph baseGraph, ProjectGraph candidateGraph, List<ExecutionDiagnostic> diagnostics)
      implements ChangePlanningResult {
      public Success {
         Objects.requireNonNull(analysis, "analysis");
         Objects.requireNonNull(baseGraph, "baseGraph");
         Objects.requireNonNull(candidateGraph, "candidateGraph");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
            throw new IllegalArgumentException("Success must not contain ERROR diagnostics");
         }
      }
   }
}
