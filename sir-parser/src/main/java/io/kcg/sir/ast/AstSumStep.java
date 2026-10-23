package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** One exact Decimal measurement, distinct from a row count or an existence read. */
public record AstSumStep(AstNodeId id, SourceSpan span, AstNameRef entity, AstNameRef field,
                         AstExpression predicate, AstName result) implements AstStep {
    public AstSumStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(result, "result");
    }
}
