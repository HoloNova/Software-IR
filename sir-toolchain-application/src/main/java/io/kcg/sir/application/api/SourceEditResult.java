package io.kcg.sir.application.api;

import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public sealed interface SourceEditResult permits SourceEditResult.Saved, SourceEditResult.Refused, SourceEditResult.Failure {
    List<ExecutionDiagnostic> diagnostics();
    record Saved(Path candidateRoot, SourceSnapshot sources, SemanticDelta delta, List<EditResult.LocatedChange> changes,
            List<ExecutionDiagnostic> diagnostics) implements SourceEditResult {
        public Saved {
            Objects.requireNonNull(candidateRoot); Objects.requireNonNull(sources); Objects.requireNonNull(delta);
            changes = List.copyOf(changes); diagnostics = List.copyOf(diagnostics);
        }
    }
    record Refused(EditResult.Refused refusal, List<ExecutionDiagnostic> diagnostics) implements SourceEditResult {
        public Refused { Objects.requireNonNull(refusal); diagnostics = List.copyOf(diagnostics); }
    }
    record Failure(List<ExecutionDiagnostic> diagnostics) implements SourceEditResult {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }
}
