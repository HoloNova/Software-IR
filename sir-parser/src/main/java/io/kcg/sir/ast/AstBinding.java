package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstBinding(
        AstNodeId id,
        SourceSpan span,
        AstNameRef fieldName,
        AstExpression value) implements AstNode {

    public AstBinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(value, "value");
    }
}
