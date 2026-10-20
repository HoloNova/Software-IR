package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * {@code unique (a, b);} in an entity: no two rows hold the same values in all of these fields together. The fields
 * are the entity's own and at least two, in the order the author wrote them (the order of the index).
 */
public record AstUniqueKey(AstNodeId id, SourceSpan span, List<AstNameRef> fields) implements AstNode {
    public AstUniqueKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
