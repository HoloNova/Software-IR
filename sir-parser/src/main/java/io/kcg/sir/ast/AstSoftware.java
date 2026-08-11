package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstSoftware(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstMetadata metadata,
        AstTarget target,
        List<AstDeclaration> declarations) implements AstNode {

    public AstSoftware {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(target, "target");
        declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
    }
}
