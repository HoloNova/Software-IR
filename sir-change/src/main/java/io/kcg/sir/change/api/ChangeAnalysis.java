package io.kcg.sir.change.api;

import java.util.List;
import java.util.Objects;

public sealed interface ChangeAnalysis permits ChangeAnalysis.Planned, ChangeAnalysis.NoChanges, ChangeAnalysis.Failure {
   List<ChangeDiagnostic> diagnostics();

   record Failure(List<ChangeDiagnostic> diagnostics) implements ChangeAnalysis {
      public Failure {
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().noneMatch(ChangeDiagnostic::isError)) {
            throw new IllegalArgumentException("Failure must contain at least one ERROR diagnostic");
         }
      }
   }

   record NoChanges(NoChangeReason reason, List<ChangeDiagnostic> diagnostics) implements ChangeAnalysis {
      public NoChanges {
         Objects.requireNonNull(reason, "reason");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(ChangeDiagnostic::isError)) {
            throw new IllegalArgumentException("NoChanges must not contain ERROR diagnostics");
         }
      }
   }

   record Planned(ChangePlan plan, List<ChangeDiagnostic> diagnostics) implements ChangeAnalysis {
      public Planned {
         Objects.requireNonNull(plan, "plan");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(ChangeDiagnostic::isError)) {
            throw new IllegalArgumentException("Planned must not contain ERROR diagnostics");
         }
      }
   }
}
