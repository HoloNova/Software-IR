package io.kcg.sir.semantic.type;

import java.util.Objects;

public record ListType(SirType element) implements SirType {
   public ListType {
      Objects.requireNonNull(element, "element");
   }
}
