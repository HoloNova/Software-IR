package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstOptionalTypeRef(
        AstNodeId id,
        SourceSpan span,
        AstTypeRef elementType) implements AstTypeRef {

    public AstOptionalTypeRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(elementType, "elementType");
    }
}
