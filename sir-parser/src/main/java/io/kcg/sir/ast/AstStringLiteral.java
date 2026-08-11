package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstStringLiteral(
        AstNodeId id,
        SourceSpan span,
        String value) implements AstExpression {

    public AstStringLiteral {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(value, "value");
    }
}
