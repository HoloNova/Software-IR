package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstGroupedExpression(
        AstNodeId id,
        SourceSpan span,
        AstExpression inner) implements AstExpression {

    public AstGroupedExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(inner, "inner");
    }
}
