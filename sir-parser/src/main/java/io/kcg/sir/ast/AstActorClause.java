package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstActorClause(
        AstNodeId id,
        SourceSpan span,
        AstTypeRef type) implements AstCapabilityClause {

    public AstActorClause {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(type, "type");
    }
}
