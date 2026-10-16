package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AstEntityDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstIdentity identity,
        List<AstField> fields,
        Optional<AstCredentialClause> credential) implements AstDeclaration {

    public AstEntityDecl(AstNodeId id, SourceSpan span, AstName name, AstIdentity identity, List<AstField> fields) {
        this(id, span, name, identity, fields, Optional.empty());
    }

    public AstEntityDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identity, "identity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        Objects.requireNonNull(credential, "credential");
    }
}
