package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/**
 * One projected field of a {@link NormalizedView}.
 *
 * <p>{@link #id()} identifies the projected field itself, while
 * {@link #sourceField()} identifies the entity field it reads. Keeping both
 * means the projection has its own stable identity without losing the column it
 * came from.
 */
public record NormalizedViewField(
   SymbolId id,
   String name,
   SourceSpan span,
   AstNodeId sourceNodeId,
   SirType type,
   SymbolId sourceField,
   Optional<AstNodeId> directNamedTypeReferenceSiteId
) {
   public NormalizedViewField {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(sourceField, "sourceField");
      Objects.requireNonNull(directNamedTypeReferenceSiteId, "directNamedTypeReferenceSiteId");
   }
}
