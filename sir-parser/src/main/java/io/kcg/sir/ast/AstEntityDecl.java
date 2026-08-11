package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstEntityDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstIdentity identity,
        List<AstField> fields) implements AstDeclaration {

    public AstEntityDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identity, "identity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
