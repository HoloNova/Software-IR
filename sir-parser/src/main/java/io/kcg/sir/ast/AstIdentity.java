package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstIdentity(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstTypeRef type,
        AstGenerationStrategy generation) implements AstNode {

    public AstIdentity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(generation, "generation");
    }
}
