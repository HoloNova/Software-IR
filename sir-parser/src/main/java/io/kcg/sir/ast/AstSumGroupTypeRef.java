package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** {@code SumGroup<Ref<Entity>>}: the element type of a grouped exact total, never a stored column type. */
public record AstSumGroupTypeRef(
        AstNodeId id,
        SourceSpan span,
        AstTypeRef elementType) implements AstTypeRef {

    public AstSumGroupTypeRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(elementType, "elementType");
    }
}
