package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

/**
 * One key of a {@code find} step's {@code order by} clause.
 *
 * <p>{@code descending} is explicit because "ascending" and "no direction
 * given" must mean the same thing, and a boolean keeps that unambiguous.
 */
public record AstFindOrderKey(
        AstNodeId id,
        SourceSpan span,
        AstNameRef field,
        boolean descending) implements AstNode {

    public AstFindOrderKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(field, "field");
    }
}
