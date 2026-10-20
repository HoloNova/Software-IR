package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code scope}, when present, is the condition a row must meet to exist for the software; see the grammar.
 * {@code uniqueKeys} are the combinations of fields no two rows may share.
 */
public record AstEntityDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstIdentity identity,
        List<AstField> fields,
        Optional<AstCredentialClause> credential,
        Optional<AstExpression> scope,
        List<AstUniqueKey> uniqueKeys) implements AstDeclaration {

    public AstEntityDecl(AstNodeId id, SourceSpan span, AstName name, AstIdentity identity, List<AstField> fields) {
        this(id, span, name, identity, fields, Optional.empty(), Optional.empty(), List.of());
    }

    public AstEntityDecl(AstNodeId id, SourceSpan span, AstName name, AstIdentity identity, List<AstField> fields,
            Optional<AstCredentialClause> credential, Optional<AstExpression> scope) {
        this(id, span, name, identity, fields, credential, scope, List.of());
    }

    public AstEntityDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(identity, "identity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(scope, "scope");
        uniqueKeys = List.copyOf(Objects.requireNonNull(uniqueKeys, "uniqueKeys"));
    }
}
