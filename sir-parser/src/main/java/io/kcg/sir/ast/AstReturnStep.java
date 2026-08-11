package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstReturnStep(
        AstNodeId id,
        SourceSpan span,
        AstExpression value) implements AstStep {

    public AstReturnStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(value, "value");
    }
}
