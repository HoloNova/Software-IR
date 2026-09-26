package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A declared member.
 *
 * <p>{@code declaredId} is the persistent declaration id written as {@code @id("...")}. The
 * grammar only admits it on entity members; input and view fields always carry
 * {@link Optional#empty()} because their identity stays name-derived. A field with a declared id
 * keeps that id when its name changes, and a field re-created under the same name with a different
 * declared id is a different declaration.
 */
public record AstField(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstTypeRef type,
        List<AstConstraint> constraints,
        boolean versioned,
        Optional<String> declaredId) implements AstNode {

    public AstField(
            AstNodeId id,
            SourceSpan span,
            AstName name,
            AstTypeRef type,
            List<AstConstraint> constraints,
            boolean versioned) {
        this(id, span, name, type, constraints, versioned, Optional.empty());
    }

    public AstField(
            AstNodeId id,
            SourceSpan span,
            AstName name,
            AstTypeRef type,
            List<AstConstraint> constraints) {
        this(id, span, name, type, constraints, false, Optional.empty());
    }

    public AstField {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
        Objects.requireNonNull(declaredId, "declaredId");
    }
}
