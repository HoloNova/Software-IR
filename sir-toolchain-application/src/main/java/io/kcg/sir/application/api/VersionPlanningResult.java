package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

/** The outcome of planning a version change. Planning never writes. */
public sealed interface VersionPlanningResult permits VersionPlanningResult.Planned, VersionPlanningResult.NoChanges, VersionPlanningResult.Failure {
    List<ExecutionDiagnostic> diagnostics();

    record Planned(VersionPlan plan, List<ExecutionDiagnostic> diagnostics) implements VersionPlanningResult {
        public Planned {
            Objects.requireNonNull(plan, "plan");
            diagnostics = clean(diagnostics);
        }
    }

    /** The candidate is the stored version itself: same sources, same generated files. */
    record NoChanges(ProjectBaselineReceipt baseline, List<ExecutionDiagnostic> diagnostics) implements VersionPlanningResult {
        public NoChanges {
            Objects.requireNonNull(baseline, "baseline");
            diagnostics = clean(diagnostics);
        }
    }

    record Failure(ExecutionStage failedStage, List<ExecutionDiagnostic> diagnostics) implements VersionPlanningResult {
        public Failure {
            Objects.requireNonNull(failedStage, "failedStage");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("a failure needs an ERROR diagnostic");
            }
        }

        public FailureDisposition disposition() {
            return FailureDisposition.NO_CHANGES;
        }
    }

    private static List<ExecutionDiagnostic> clean(List<ExecutionDiagnostic> diagnostics) {
        List<ExecutionDiagnostic> copy = List.copyOf(diagnostics);
        if (copy.stream().anyMatch(ExecutionDiagnostic::isError)) {
            throw new IllegalArgumentException("a successful result has no ERROR diagnostic");
        }

        return copy;
    }
}
