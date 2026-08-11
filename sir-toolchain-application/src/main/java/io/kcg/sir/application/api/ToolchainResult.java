package io.kcg.sir.application.api;

import io.kcg.sir.projectgraph.api.ProjectGraph;
import java.util.List;
import java.util.Objects;

public sealed interface ToolchainResult permits ToolchainResult.Success, ToolchainResult.Failure {
   List<ExecutionDiagnostic> diagnostics();

   record Failure(ExecutionStage failedStage, FailureDisposition disposition, List<ExecutionDiagnostic> diagnostics) implements ToolchainResult {
      public Failure {
         Objects.requireNonNull(failedStage, "failedStage");
         Objects.requireNonNull(disposition, "disposition");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
            throw new IllegalArgumentException("Failure must contain at least one ERROR diagnostic");
         }
      }
   }

   record Success(ExecutionManifest manifest, ProjectGraph graph, List<ExecutionDiagnostic> diagnostics) implements ToolchainResult {
      public Success {
         Objects.requireNonNull(manifest, "manifest");
         Objects.requireNonNull(graph, "graph");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) {
            throw new IllegalArgumentException("Success must not contain ERROR diagnostics");
         }
      }
   }
}
