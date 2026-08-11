package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigDecimal;
import java.util.Objects;

public record AstDecimalLiteral(
        AstNodeId id,
        SourceSpan span,
        BigDecimal value) implements AstExpression {

    public AstDecimalLiteral {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(value, "value");
    }
}
