package io.kcg.sir.api;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record Diagnostic(
        DiagnosticCode code,
        DiagnosticSeverity severity,
        String message,
        SourceSpan primarySpan,
        List<RelatedLocation> related,
        List<FixHint> fixes) {

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(primarySpan, "primarySpan");
        related = List.copyOf(Objects.requireNonNull(related, "related"));
        fixes = List.copyOf(Objects.requireNonNull(fixes, "fixes"));
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public boolean isError() {
        return severity == DiagnosticSeverity.ERROR;
    }
}
