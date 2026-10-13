package io.kcg.sir.semantic.type;

import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record RefType(@Fact String entityName, @Reference SymbolId entityId) implements SirType {
   public RefType {
      Objects.requireNonNull(entityName, "entityName");
      Objects.requireNonNull(entityId, "entityId");
   }
}
