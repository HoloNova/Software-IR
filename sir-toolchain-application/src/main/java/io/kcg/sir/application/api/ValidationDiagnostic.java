package io.kcg.sir.application.api;

import io.kcg.sir.api.FixHint;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/** Lossless stage diagnostic, separate from the deliberately frozen legacy projection. */
public record ValidationDiagnostic(String code, String severity, ValidationStopAfter stage, String message,
    SourceSpan span, List<RelatedLocation> related, List<FixHint> fixes, String sourceSymbol,
    String sourceNodeId, String loweredNodeId, String relativePath) {
    public ValidationDiagnostic {
        Objects.requireNonNull(code); Objects.requireNonNull(severity); Objects.requireNonNull(stage); Objects.requireNonNull(message);
        related = List.copyOf(related); fixes = List.copyOf(fixes);
    }
    public static ValidationDiagnostic error(ValidationStopAfter stage, String code, String message) {
        return new ValidationDiagnostic(code, "ERROR", stage, message, null, List.of(), List.of(), null, null, null, null);
    }
}
