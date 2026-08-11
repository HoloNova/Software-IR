package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record DeclaredType(DeclaredType.DeclaredKind kind, String name, SymbolId symbolId) implements SirType {
   public DeclaredType {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(symbolId, "symbolId");
   }

   public enum DeclaredKind {
      ENUM,
      ENTITY,
      INPUT;
   }
}
