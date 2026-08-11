package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstLoadStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstExpression idExpression,
        AstName result,
        AstNameRef error) implements AstStep {

    public AstLoadStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(idExpression, "idExpression");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(error, "error");
    }
}
