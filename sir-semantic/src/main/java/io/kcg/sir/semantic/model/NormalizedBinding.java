package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record NormalizedBinding(@Fact String fieldName, @Derived SymbolId fieldSymbol, @Fact NormalizedExpression value) {
   public NormalizedBinding {
      Objects.requireNonNull(fieldName, "fieldName");
      Objects.requireNonNull(fieldSymbol, "fieldSymbol");
      Objects.requireNonNull(value, "value");
   }
}
