package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * The Q9 response-projection declaration: {@code view <Name> from <Entity> { field ...; }}.
 *
 * <p>The declaration only records the authored shape. Binding each projected
 * field to a field of {@link #sourceEntity()} is the resolver's job, exactly
 * like any other reference site.
 */
public record AstViewDecl(
        AstNodeId id,
        SourceSpan span,
        AstName name,
        AstNameRef sourceEntity,
        List<AstViewField> fields) implements AstDeclaration {

    public AstViewDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sourceEntity, "sourceEntity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
