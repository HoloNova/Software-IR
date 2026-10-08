package io.kcg.sir.change.internal;

import io.kcg.sir.change.api.*;
import java.util.List;

/** Shared pure decision; each public API wraps it in its own admitted revision contract. */
sealed interface WorkflowDecision {
    record Failure(List<ChangeDiagnostic> diagnostics) implements WorkflowDecision {}
    record NoChanges(NoChangeReason reason, List<ChangeDiagnostic> diagnostics) implements WorkflowDecision {}
    record Planned(List<ArtifactChange> artifacts, List<FileChange> files, List<ChangeDiagnostic> diagnostics) implements WorkflowDecision {}
}
