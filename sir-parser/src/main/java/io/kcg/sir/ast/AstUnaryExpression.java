package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstUnaryExpression(
        AstNodeId id,
        SourceSpan span,
        AstUnaryOperator operator,
        AstExpression operand) implements AstExpression {

    public AstUnaryExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operand, "operand");
    }
}
