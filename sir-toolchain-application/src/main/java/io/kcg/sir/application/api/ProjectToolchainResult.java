package io.kcg.sir.application.api;

import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.SourceSnapshot;
import java.util.*;

public sealed interface ProjectToolchainResult permits ProjectToolchainResult.Success, ProjectToolchainResult.Failure {
    List<ExecutionDiagnostic> diagnostics();
    record Success(SourceSnapshot sources, ExecutionManifest manifest, ProjectGraph graph,
                   List<ExecutionDiagnostic> diagnostics) implements ProjectToolchainResult {
        public Success {
            Objects.requireNonNull(sources); Objects.requireNonNull(manifest); Objects.requireNonNull(graph);
            diagnostics = List.copyOf(diagnostics);
            if (graph.version() != GraphVersion.V0_2 || diagnostics.stream().anyMatch(ExecutionDiagnostic::isError))
                throw new IllegalArgumentException("project success requires a V0_2 graph without errors");
            var project = graph.nodes().stream().filter(n -> n instanceof ProjectGraphNode.Project)
                    .map(n -> (ProjectGraphNode.Project) n).findFirst().orElseThrow();
            if (!project.provenance().sourceSet().orElseThrow().equals(sources.manifest()))
                throw new IllegalArgumentException("graph must bind the returned source snapshot");
        }
    }
    record Failure(ExecutionStage failedStage, FailureDisposition disposition,
                   List<ExecutionDiagnostic> diagnostics) implements ProjectToolchainResult {
        public Failure {
            Objects.requireNonNull(failedStage); Objects.requireNonNull(disposition); diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) throw new IllegalArgumentException("failure requires a diagnostic");
        }
    }
}
