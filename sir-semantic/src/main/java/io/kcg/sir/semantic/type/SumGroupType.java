package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

/**
 * {@code SumGroup<Ref<Entity>>}: one reference key and its exact total.
 *
 * <p>The total is the aggregate's own {@link PrimitiveType#DECIMAL}, not the stored measurement's scale: a sum of
 * many two-place amounts may need more fraction digits than any single row carries. Only a direct, non-optional
 * reference key is accepted, so {@code COUNT(DISTINCT key)} and {@code GROUP BY key} count the same groups.
 */
public record SumGroupType(@Fact RefType key) implements SirType {
   public SumGroupType {
      Objects.requireNonNull(key, "key");
   }
}
