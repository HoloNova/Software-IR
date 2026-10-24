package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** A view's complete related-row count, never a general expression with an implicit query. */
public record AstCountExpression(AstNodeId id, SourceSpan span, AstNameRef entity,
                                 AstExpression conditions) implements AstExpression {
    public AstCountExpression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(conditions, "conditions");
    }
}
