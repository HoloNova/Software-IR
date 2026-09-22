package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * One projected field of a {@code view} declaration.
 *
 * <p>The name is an {@link AstNameRef} rather than a plain {@link AstName}
 * because a projected name is a reference site: it must bind once to the field
 * of the source entity it reads. The declared type is checked against that
 * bound field, and constraints are parsed so that a later phase can reject them
 * instead of the grammar silently dropping them.
 */
public record AstViewField(
        AstNodeId id,
        SourceSpan span,
        AstNameRef name,
        AstTypeRef type,
        List<AstConstraint> constraints,
        boolean versioned) implements AstNode {

    public AstViewField(
            AstNodeId id,
            SourceSpan span,
            AstNameRef name,
            AstTypeRef type,
            List<AstConstraint> constraints) {
        this(id, span, name, type, constraints, false);
    }

    public AstViewField {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
    }
}
