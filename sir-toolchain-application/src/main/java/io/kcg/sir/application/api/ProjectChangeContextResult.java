package io.kcg.sir.application.api;

import java.util.*;

public sealed interface ProjectChangeContextResult {
    List<ProjectChangeDiagnostic> diagnostics();
    record Success(ProjectChangeContext context,List<ProjectChangeDiagnostic> diagnostics) implements ProjectChangeContextResult {
        public Success { Objects.requireNonNull(context);diagnostics=List.copyOf(diagnostics);if(diagnostics.stream().anyMatch(ProjectChangeDiagnostic::isError))throw new IllegalArgumentException("success has ERROR"); }
    }
    record Failure(ProjectChangeStage failedStage,List<ProjectChangeDiagnostic> diagnostics) implements ProjectChangeContextResult {
        public Failure { Objects.requireNonNull(failedStage);diagnostics=List.copyOf(diagnostics);if(diagnostics.stream().noneMatch(ProjectChangeDiagnostic::isError))throw new IllegalArgumentException("failure needs ERROR"); }
        public FailureDisposition disposition() { return FailureDisposition.NO_CHANGES; }
    }
}
