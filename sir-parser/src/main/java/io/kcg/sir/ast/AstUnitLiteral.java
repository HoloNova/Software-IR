package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstUnitLiteral(
        AstNodeId id,
        SourceSpan span) implements AstExpression {

    public AstUnitLiteral {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
    }
}
