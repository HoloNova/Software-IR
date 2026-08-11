package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record AstEnumDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        List<AstEnumMember> members) implements AstDeclaration {

    public AstEnumDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
