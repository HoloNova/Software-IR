package io.kcg.sir.change.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record ChangeTarget(SymbolId declarationSymbol, AstNodeId declarationNodeId, AstNodeId targetNodeId) {
   public ChangeTarget {
      Objects.requireNonNull(declarationSymbol, "declarationSymbol");
      Objects.requireNonNull(declarationNodeId, "declarationNodeId");
      Objects.requireNonNull(targetNodeId, "targetNodeId");
   }
}
