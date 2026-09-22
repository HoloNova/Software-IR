package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@code input} declaration.
 *
 * <p>A plain input is a request payload. A <em>patch</em> input
 * ({@code input X patch of Course { ... }}) additionally names the entity whose
 * fields it is allowed to change; that name is a reference site like any other,
 * so binding it is the resolver's job. The declared change fields are parsed with
 * the shared field rule, which is why the patch marker is not enforced by the
 * grammar alone.
 */
public record AstInputDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        Optional<AstNameRef> patchSourceEntity,
        List<AstField> fields) implements AstDeclaration {

    public AstInputDecl(
            AstNodeId id,
            SourceSpan span,
            AstName name,
            List<AstField> fields) {
        this(id, span, name, Optional.empty(), fields);
    }

    public AstInputDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(patchSourceEntity, "patchSourceEntity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
