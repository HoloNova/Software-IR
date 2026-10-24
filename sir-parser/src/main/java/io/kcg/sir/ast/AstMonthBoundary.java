package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/** One independently bound local definition of a calendar step. */
public record AstMonthBoundary(AstNodeId id, SourceSpan span, AstName name) implements AstNode {
    public AstMonthBoundary {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(span, "span"); Objects.requireNonNull(name, "name");
    }
}
