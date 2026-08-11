package io.kcg.sir.projectgraph.api;

import java.util.List;
import java.util.Objects;

public sealed interface ProjectGraphAnalysis permits ProjectGraphAnalysis.Success, ProjectGraphAnalysis.Failure {
   List<ProjectGraphDiagnostic> diagnostics();

   default boolean isSuccess() {
      return this instanceof ProjectGraphAnalysis.Success;
   }

   record Failure(List<ProjectGraphDiagnostic> diagnostics) implements ProjectGraphAnalysis {
      public Failure {
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().noneMatch(ProjectGraphDiagnostic::isError)) {
            throw new IllegalArgumentException("Failure must contain at least one ERROR diagnostic");
         }
      }
   }

   record Success(ProjectGraph graph, List<ProjectGraphDiagnostic> diagnostics) implements ProjectGraphAnalysis {
      public Success {
         Objects.requireNonNull(graph, "graph");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
            throw new IllegalArgumentException("Success must not contain ERROR diagnostics");
         }
      }
   }
}
