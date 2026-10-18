package io.kcg.sir.ast;

import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * {@code extend Entity { fields }}: fields the entity has although its own declaration does not list them. They are
 * the entity's fields like any other; only where they are written differs.
 */
public record AstEntityExtensionDecl(AstNodeId id, SourceSpan span, AstNameRef entity, List<AstField> fields) implements AstDeclaration {
    public AstEntityExtensionDecl {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(entity, "entity");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }
}
