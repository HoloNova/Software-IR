package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

/** The outcome of applying a version change. */
public sealed interface VersionApplyResult permits VersionApplyResult.Applied, VersionApplyResult.NoChanges, VersionApplyResult.Failure, VersionApplyResult.RecoveryRequired {
    List<ExecutionDiagnostic> diagnostics();

    /** Whether the pointer to the current version already names the candidate. */
    enum Publication { NOT_PUBLISHED, PUBLISHED, UNKNOWN }

    /** The candidate is now the stored current version and the generated project matches it. */
    record Applied(ProjectBaselineReceipt base, ProjectBaselineReceipt candidate, List<ExecutionDiagnostic> diagnostics) implements VersionApplyResult {
        public Applied {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(candidate, "candidate");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record NoChanges(ProjectBaselineReceipt baseline, List<ExecutionDiagnostic> diagnostics) implements VersionApplyResult {
        public NoChanges {
            Objects.requireNonNull(baseline, "baseline");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Refused before anything was changed. */
    record Failure(ExecutionStage failedStage, List<ExecutionDiagnostic> diagnostics) implements VersionApplyResult {
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

    /** Stopped after the first change: the project is in between and needs explicit recovery. */
    record RecoveryRequired(VersionRecoveryHandle handle, Publication publication, List<ExecutionDiagnostic> diagnostics) implements VersionApplyResult {
        public RecoveryRequired {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(publication, "publication");
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
