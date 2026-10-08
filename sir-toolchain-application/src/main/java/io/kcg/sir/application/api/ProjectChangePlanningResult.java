package io.kcg.sir.application.api;

import io.kcg.sir.change.api.*;
import io.kcg.sir.application.internal.changeplanning.ProjectContextHasher;
import java.util.*;

public sealed interface ProjectChangePlanningResult {
    List<ProjectChangeDiagnostic> diagnostics();
    record Planned(ProjectChangeContext context,ProjectChangePlan plan,List<ProjectChangeDiagnostic> diagnostics) implements ProjectChangePlanningResult {
        public Planned { Objects.requireNonNull(context);Objects.requireNonNull(plan);diagnostics=clean(diagnostics); }
        public String sha256Hex() { return ProjectContextHasher.plan(context.contextId(),plan.sha256Hex()); }
    }
    record NoChanges(ProjectChangeContext context,NoChangeReason reason,List<ProjectChangeDiagnostic> diagnostics) implements ProjectChangePlanningResult {
        public NoChanges { Objects.requireNonNull(context);Objects.requireNonNull(reason);diagnostics=clean(diagnostics); }
    }
    record Failure(ProjectChangeStage failedStage,List<ProjectChangeDiagnostic> diagnostics) implements ProjectChangePlanningResult {
        public Failure { Objects.requireNonNull(failedStage);diagnostics=List.copyOf(diagnostics);if(diagnostics.stream().noneMatch(ProjectChangeDiagnostic::isError))throw new IllegalArgumentException("failure needs ERROR"); }
        public FailureDisposition disposition() { return FailureDisposition.NO_CHANGES; }
    }
    private static List<ProjectChangeDiagnostic> clean(List<ProjectChangeDiagnostic> ds) { var copy=List.copyOf(ds);if(copy.stream().anyMatch(ProjectChangeDiagnostic::isError))throw new IllegalArgumentException("success has ERROR");return copy; }
}
