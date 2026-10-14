package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

/** The outcome of planning a change against the SIR on disk. Planning never writes. */
public sealed interface WorkspacePlanningResult permits WorkspacePlanningResult.Planned, WorkspacePlanningResult.NoChanges, WorkspacePlanningResult.Failure {
    List<ExecutionDiagnostic> diagnostics();

    record Planned(WorkspacePlan plan, List<ExecutionDiagnostic> diagnostics) implements WorkspacePlanningResult {
        public Planned {
            Objects.requireNonNull(plan, "plan");
            diagnostics = clean(diagnostics);
        }
    }

    /** The candidate is the version already on disk: same sources, same generated files. */
    record NoChanges(ProjectBaselineReceipt baseline, List<ExecutionDiagnostic> diagnostics) implements WorkspacePlanningResult {
        public NoChanges {
            Objects.requireNonNull(baseline, "baseline");
            diagnostics = clean(diagnostics);
        }
    }

    /**
     * Refused, with nothing changed. This includes "the generated project does not match the SIR on disk":
     * the user regenerates the project from the current SIR, or restores it from Git, and plans again.
     */
    record Failure(ExecutionStage failedStage, List<ExecutionDiagnostic> diagnostics) implements WorkspacePlanningResult {
        public Failure {
            Objects.requireNonNull(failedStage, "failedStage");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("a failure needs an ERROR diagnostic");
            }
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
