package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstNamedTypeRef(
        AstNodeId id,
        SourceSpan span,
        AstNameRef name) implements AstTypeRef {

    public AstNamedTypeRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
    }
}
