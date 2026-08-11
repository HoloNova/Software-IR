package io.kcg.sir.semantic.api;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record ReferenceSiteBinding(ReferenceSite site, SymbolId targetSymbol) {
   public ReferenceSiteBinding {
      Objects.requireNonNull(site, "site");
      Objects.requireNonNull(targetSymbol, "targetSymbol");
   }
}
