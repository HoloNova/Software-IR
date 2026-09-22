package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * A {@code view} declaration: a response projection of an entity.
 *
 * <p>{@link #sourceEntity()} is the resolved entity symbol, and every field
 * keeps the identity of the entity field it reads in
 * {@link NormalizedViewField#sourceField()}. Later phases therefore never have
 * to re-derive a projection from names.
 */
public record NormalizedView(
   SymbolId id,
   String name,
   SourceSpan span,
   AstNodeId sourceNodeId,
   SymbolId sourceEntity,
   List<NormalizedViewField> fields
) implements NormalizedDeclaration {
   public NormalizedView {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(sourceEntity, "sourceEntity");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
   }
}
