package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record NormalizedBinding(String fieldName, SymbolId fieldSymbol, NormalizedExpression value) {
   public NormalizedBinding {
      Objects.requireNonNull(fieldName, "fieldName");
      Objects.requireNonNull(fieldSymbol, "fieldSymbol");
      Objects.requireNonNull(value, "value");
   }
}
