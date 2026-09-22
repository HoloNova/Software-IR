package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

public record AstFindStep(
        AstNodeId id,
        SourceSpan span,
        AstNameRef entity,
        AstExpression predicate,
        Optional<AstFindOrder> order,
        Optional<AstFindPage> page,
        AstName result) implements AstStep {

    public AstFindStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(result, "result");
    }
}
