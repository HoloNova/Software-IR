package io.kcg.sir.lowering.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

public record LoweringDiagnostic(
        LoweringDiagnosticCode code,
        LoweringSeverity severity,
        String message,
        SourceSpan primarySpan,
        Optional<SymbolId> sourceSymbol,
        Optional<AstNodeId> sourceNodeId
) {
    public LoweringDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(primarySpan, "primarySpan");
        Objects.requireNonNull(sourceSymbol, "sourceSymbol");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
