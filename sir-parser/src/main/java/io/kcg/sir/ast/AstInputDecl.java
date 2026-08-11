package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstInputDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        List<AstField> fields) implements AstDeclaration {

    public AstInputDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
