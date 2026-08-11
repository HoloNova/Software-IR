package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstNowExpression(
        AstNodeId id,
        SourceSpan span) implements AstExpression {

    public AstNowExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
    }
}
