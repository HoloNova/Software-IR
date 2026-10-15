package io.kcg.sir.application.api;

import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.source.SourceSnapshot;
import java.util.List;
import java.util.Objects;

public sealed interface SourceInspectionResult permits SourceInspectionResult.Success, SourceInspectionResult.Failure {
    List<ExecutionDiagnostic> diagnostics();
    record Success(SourceSnapshot sources, SoftwareSchema schema, SoftwareDraft draft, List<ExecutionDiagnostic> diagnostics)
            implements SourceInspectionResult {
        public Success {
            Objects.requireNonNull(sources); Objects.requireNonNull(schema); Objects.requireNonNull(draft);
            diagnostics = List.copyOf(diagnostics);
        }
    }
    record Failure(List<ExecutionDiagnostic> diagnostics) implements SourceInspectionResult {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }
}
