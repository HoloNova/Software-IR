package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The outcome of applying a change to the SIR and the generated project on disk. */
public sealed interface WorkspaceApplyResult permits WorkspaceApplyResult.Applied, WorkspaceApplyResult.NoChanges, WorkspaceApplyResult.Failure, WorkspaceApplyResult.RerunRequired {
    List<ExecutionDiagnostic> diagnostics();

    /**
     * The sources and the generated project are now the candidate. {@code beforeCommit} is the Git commit
     * that saved the previous version; {@code afterCommit} is the one that saved the new version, and is
     * empty when saving it failed (a WARNING says why; the files themselves are complete).
     */
    record Applied(ProjectBaselineReceipt base, ProjectBaselineReceipt candidate, Optional<String> beforeCommit, Optional<String> afterCommit,
            List<ExecutionDiagnostic> diagnostics) implements WorkspaceApplyResult {
        public Applied {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(beforeCommit, "beforeCommit");
            Objects.requireNonNull(afterCommit, "afterCommit");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record NoChanges(ProjectBaselineReceipt baseline, List<ExecutionDiagnostic> diagnostics) implements WorkspaceApplyResult {
        public NoChanges {
            Objects.requireNonNull(baseline, "baseline");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Refused before anything was changed, for example because Git is not available. */
    record Failure(ExecutionStage failedStage, List<ExecutionDiagnostic> diagnostics) implements WorkspaceApplyResult {
        public Failure {
            Objects.requireNonNull(failedStage, "failedStage");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("a failure needs an ERROR diagnostic");
            }
        }
    }

    /**
     * Stopped after the first change: some files may already be the new version. Nothing is repaired
     * automatically. The user runs the same apply again, which carries on from where it stopped, or
     * returns to the saved previous version with Git.
     */
    record RerunRequired(Optional<String> beforeCommit, List<ExecutionDiagnostic> diagnostics) implements WorkspaceApplyResult {
        public RerunRequired {
            Objects.requireNonNull(beforeCommit, "beforeCommit");
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.stream().noneMatch(ExecutionDiagnostic::isError)) {
                throw new IllegalArgumentException("an interrupted apply needs an ERROR diagnostic");
            }
        }
    }
}
