package io.kcg.sir.semantic.type;

import java.util.Objects;

public record OptionalType(SirType element) implements SirType {
   public OptionalType {
      Objects.requireNonNull(element, "element");
   }
}
