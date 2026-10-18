package io.kcg.sir.semantic.api;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

/**
 * Where an expanded declaration came from: the {@code use} declaration that asked for it, and its own name in the
 * feature (without any prefix). An expanded declaration is analysed like any other, but it is not source text: it
 * changes only when the {@code use} declaration does.
 */
public record FeatureOrigin(SymbolId feature, String role) {
   public FeatureOrigin {
      Objects.requireNonNull(feature, "feature");
      Objects.requireNonNull(role, "role");
   }
}
