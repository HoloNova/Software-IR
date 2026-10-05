package io.kcg.sir.application.api;

import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.source.SourceSnapshot;
import java.util.*;

public sealed interface ProjectBaselineResult permits ProjectBaselineResult.Success,ProjectBaselineResult.Failure {
    List<ExecutionDiagnostic> diagnostics();
    enum Outcome { REGISTERED, ALREADY_REGISTERED, VERIFIED }
    record Success(Outcome outcome,ProjectBaselineReceipt receipt,SourceSnapshot sources,ProjectGraph graph,List<ExecutionDiagnostic> diagnostics) implements ProjectBaselineResult {
        public Success {
            Objects.requireNonNull(outcome);Objects.requireNonNull(receipt);Objects.requireNonNull(sources);Objects.requireNonNull(graph);diagnostics=List.copyOf(diagnostics);
            if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) throw new IllegalArgumentException("success with error diagnostics");
        }
    }
    record Failure(ExecutionStage failedStage,FailureDisposition disposition,List<ExecutionDiagnostic> diagnostics) implements ProjectBaselineResult {
        public Failure { Objects.requireNonNull(failedStage);Objects.requireNonNull(disposition);diagnostics=List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) throw new IllegalArgumentException("failure without error diagnostic"); }
    }
}
