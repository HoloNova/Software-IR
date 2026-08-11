package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstBinaryExpression(
        AstNodeId id,
        SourceSpan span,
        AstExpression left,
        AstBinaryOperator operator,
        AstExpression right) implements AstExpression {

    public AstBinaryExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(right, "right");
    }
}
