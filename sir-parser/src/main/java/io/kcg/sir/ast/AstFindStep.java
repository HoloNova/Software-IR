package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstFindStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstExpression predicate,
        AstName result) implements AstStep {

    public AstFindStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(result, "result");
    }
}
