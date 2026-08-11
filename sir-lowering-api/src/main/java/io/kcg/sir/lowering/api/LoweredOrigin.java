package io.kcg.sir.lowering.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

public record LoweredOrigin(
        Optional<SymbolId> ownerSymbol,
        AstNodeId sourceNodeId,
        SourceSpan span
) {
    public LoweredOrigin {
        Objects.requireNonNull(ownerSymbol, "ownerSymbol");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(span, "span");
    }
}
