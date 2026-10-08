package io.kcg.sir.change.api;

import java.util.*;

public sealed interface ProjectChangeAnalysis {
    List<ChangeDiagnostic> diagnostics();
    record Planned(ProjectChangePlan plan, List<ChangeDiagnostic> diagnostics) implements ProjectChangeAnalysis {
        public Planned { Objects.requireNonNull(plan);diagnostics=clean(diagnostics); }
    }
    record NoChanges(ProjectChangeRevision basedOn,ProjectChangeRevision candidate,NoChangeReason reason,List<ChangeDiagnostic> diagnostics) implements ProjectChangeAnalysis {
        public NoChanges { Objects.requireNonNull(basedOn);Objects.requireNonNull(candidate);Objects.requireNonNull(reason);diagnostics=clean(diagnostics); }
    }
    record Failure(List<ChangeDiagnostic> diagnostics) implements ProjectChangeAnalysis {
        public Failure { diagnostics=List.copyOf(diagnostics);if(diagnostics.stream().noneMatch(ChangeDiagnostic::isError))throw new IllegalArgumentException("failure needs ERROR"); }
    }
    private static List<ChangeDiagnostic> clean(List<ChangeDiagnostic> diagnostics) {
        var copy=List.copyOf(diagnostics);if(copy.stream().anyMatch(ChangeDiagnostic::isError))throw new IllegalArgumentException("success has ERROR");return copy;
    }
}
