package io.kcg.sir.semantic.symbol;

import java.util.Objects;

public record SymbolId(String value) {
   public SymbolId {
      Objects.requireNonNull(value, "value");
      if (value.isBlank()) {
         throw new IllegalArgumentException("value must not be blank");
      }
   }

   @Override
   public String toString() {
      return this.value;
   }
}
