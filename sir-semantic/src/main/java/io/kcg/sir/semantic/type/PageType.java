package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

/**
 * The {@code Page<T>} query-result container.
 *
 * <p>It mirrors {@link ListType} as a type constructor; which output positions
 * may use it is a validation rule, not a constructor rule.
 */
public record PageType(@Fact SirType element) implements SirType {
   public PageType {
      Objects.requireNonNull(element, "element");
   }
}
