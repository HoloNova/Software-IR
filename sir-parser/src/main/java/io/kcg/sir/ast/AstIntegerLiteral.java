package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.util.Objects;

public record AstIntegerLiteral(
        AstNodeId id,
        SourceSpan span,
        BigInteger value) implements AstExpression {

    public AstIntegerLiteral {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(value, "value");
    }
}
