package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstValidateStep(
        AstNodeId id,
        SourceSpan span,
        AstExpression condition,
        AstNameRef error) implements AstStep {

    public AstValidateStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(error, "error");
    }
}
