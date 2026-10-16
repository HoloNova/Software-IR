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
 *
 * <p>{@code unique} is the author's statement that no two rows hold the same value in this field.
 * Only entity members may carry it, and it is what allows a {@code load} to find a row by the field.
 *
 * <p>{@code secret} is the author's statement that the field holds a credential the system must never
 * keep or show as written: what is stored is a digest of the value, and the only way to read it is to ask
 * whether a candidate matches.
 */
public record AstField(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstTypeRef type,
        List<AstConstraint> constraints,
        boolean versioned,
        boolean unique,
        boolean secret,
        Optional<String> declaredId) implements AstNode {

    public AstField(
            AstNodeId id,
            SourceSpan span,
            AstName name,
            AstTypeRef type,
            List<AstConstraint> constraints,
            boolean versioned) {
        this(id, span, name, type, constraints, versioned, false, false, Optional.empty());
    }

    public AstField(
            AstNodeId id,
            SourceSpan span,
            AstName name,
            AstTypeRef type,
            List<AstConstraint> constraints) {
        this(id, span, name, type, constraints, false, false, false, Optional.empty());
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
