package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * The {@code order by} clause of a {@code find} step.
 *
 * <p>Keys are recorded in authored order. Appending a unique tiebreaker is a
 * Lowering decision, so the AST never invents an extra key.
 */
public record AstFindOrder(
        AstNodeId id,
        SourceSpan span,
        List<AstFindOrderKey> keys) implements AstNode {

    public AstFindOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("keys must not be empty");
        }
    }
}
