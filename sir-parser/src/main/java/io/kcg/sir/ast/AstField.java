package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstField(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstTypeRef type,
        List<AstConstraint> constraints) implements AstNode {

    public AstField {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
    }
}
