package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

public record ListType(@Fact SirType element) implements SirType {
   public ListType {
      Objects.requireNonNull(element, "element");
   }
}
