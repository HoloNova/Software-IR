package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * The {@code Page<T>} container type. It is a type constructor like
 * {@code List<T>}; which output positions may use it is a later-phase rule.
 */
public record AstPageTypeRef(
        AstNodeId id,
        SourceSpan span,
        AstTypeRef elementType) implements AstTypeRef {

    public AstPageTypeRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(elementType, "elementType");
    }
}
