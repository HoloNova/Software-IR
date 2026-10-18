package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * A {@code type} declaration: a semantic type of the author's own, written as an existing semantic type with rules
 * ({@code type Percent = Count where min(0), max(100);}). The parser records only what is written; whether the base
 * exists and the rules fit is decided by the semantic analysis.
 */
public record AstSemanticTypeDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstNameRef base,
        List<AstConstraint> constraints) implements AstDeclaration {

    public AstSemanticTypeDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(base, "base");
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
    }
}
