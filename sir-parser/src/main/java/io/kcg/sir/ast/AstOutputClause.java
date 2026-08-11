package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstOutputClause(
        AstNodeId id,
        SourceSpan span,
        AstTypeRef type) implements AstCapabilityClause {

    public AstOutputClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
    }
}
