package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record AstErrorDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name) implements AstDeclaration {

    public AstErrorDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
    }
}
