package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Fact;
import java.util.Objects;

public record OptionalType(@Fact SirType element) implements SirType {
   public OptionalType {
      Objects.requireNonNull(element, "element");
   }
}
