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
 *
 * <p>A column projection and a relation projection are told apart by
 * {@link #relation()}. A column projection reads {@code sourceField} as its own
 * value; a relation projection reads {@code sourceField} as the reference that
 * connects the two entities — on this side for {@code TO_ONE}, on the related
 * side for {@code TO_MANY} — and {@link Relation#targetView()} is the view the
 * related rows are projected with. The target therefore renders a relation by
 * reading the recorded reference, never by searching the model for one.
 */
public record NormalizedViewField(
   SymbolId id,
   String name,
   SourceSpan span,
   AstNodeId sourceNodeId,
   SirType type,
   SymbolId sourceField,
   Optional<Relation> relation,
   Optional<AstNodeId> directNamedTypeReferenceSiteId
) {
   public NormalizedViewField {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(sourceField, "sourceField");
      Objects.requireNonNull(relation, "relation");
      Objects.requireNonNull(directNamedTypeReferenceSiteId, "directNamedTypeReferenceSiteId");
   }

   /** How many related rows a nested projection reads. */
   public enum Cardinality {
      /** At most one related row, reached through a reference on this entity. */
      TO_ONE,
      /** A collection of related rows, reached through the reference they hold. */
      TO_MANY
   }

   /**
   * The relation a nested projection reads.
   *
   * <p>{@link #targetView()} is the view that projects the related rows; the reference field
   * itself stays in {@link NormalizedViewField#sourceField()}, so a target asks the field for the
   * join column and the relation for the projection to render.
   */
   public record Relation(Cardinality cardinality, SymbolId targetView) {
      public Relation {
         Objects.requireNonNull(cardinality, "cardinality");
         Objects.requireNonNull(targetView, "targetView");
      }
   }
}
