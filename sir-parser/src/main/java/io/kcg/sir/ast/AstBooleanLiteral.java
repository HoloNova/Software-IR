package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstBooleanLiteral(
        AstNodeId id,
        SourceSpan span,
        boolean value) implements AstExpression {

    public AstBooleanLiteral {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
    }
}
