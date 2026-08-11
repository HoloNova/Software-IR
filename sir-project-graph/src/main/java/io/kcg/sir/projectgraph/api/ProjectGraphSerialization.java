package io.kcg.sir.projectgraph.api;

import java.util.List;
import java.util.Objects;

public sealed interface ProjectGraphSerialization permits ProjectGraphSerialization.Success, ProjectGraphSerialization.Failure {
   List<ProjectGraphDiagnostic> diagnostics();

   default boolean isSuccess() {
      return this instanceof ProjectGraphSerialization.Success;
   }

   record Failure(List<ProjectGraphDiagnostic> diagnostics) implements ProjectGraphSerialization {
      public Failure {
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().noneMatch(ProjectGraphDiagnostic::isError)) {
            throw new IllegalArgumentException("Failure must contain at least one ERROR diagnostic");
         }
      }
   }

   record Success(CanonicalProjectGraphDocument document, List<ProjectGraphDiagnostic> diagnostics) implements ProjectGraphSerialization {
      public Success {
         Objects.requireNonNull(document, "document");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
            throw new IllegalArgumentException("Success must not contain ERROR diagnostics");
         }
      }
   }
}
