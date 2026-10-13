package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record DeclaredType(@Fact DeclaredType.DeclaredKind kind, @Fact String name, @Reference SymbolId symbolId) implements SirType {
   public DeclaredType {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(symbolId, "symbolId");
   }

   public enum DeclaredKind {
      ENUM,
      ENTITY,
      INPUT,
      VIEW;
   }
}
