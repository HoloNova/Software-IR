package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@code scope}, when present, is the condition a row must meet to exist for the software; see the grammar. */
public record AstEntityDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstIdentity identity,
        List<AstField> fields,
        Optional<AstCredentialClause> credential,
        Optional<AstExpression> scope) implements AstDeclaration {

    public AstEntityDecl(AstNodeId id, SourceSpan span, AstName name, AstIdentity identity, List<AstField> fields) {
        this(id, span, name, identity, fields, Optional.empty(), Optional.empty());
    }

    public AstEntityDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identity, "identity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(scope, "scope");
    }
}
