package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One projected field of a {@code view} declaration.
 *
 * <p>The name is an {@link AstNameRef} rather than a plain {@link AstName}
 * because a plain projected name binds to the source field it reads. With an
 * explicit expression the name is an independent output definition, and the
 * members of the expression are the reference sites. The declared type is checked
 * against the bound field or expression; constraints are parsed so a later phase can reject them
 * instead of the grammar silently dropping them.
 */
public record AstViewField(
        AstNodeId id,
        SourceSpan span,
        AstNameRef name,
        AstTypeRef type,
        List<AstConstraint> constraints,
        boolean versioned,
        Optional<AstExpression> expression) implements AstNode {

    public AstViewField(AstNodeId id, SourceSpan span, AstNameRef name, AstTypeRef type,
            List<AstConstraint> constraints, boolean versioned) {
        this(id, span, name, type, constraints, versioned, Optional.empty());
    }

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
        Objects.requireNonNull(expression, "expression");
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
    }
}
