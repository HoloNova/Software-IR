package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstGenerationStrategy;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;

public record NormalizedIdentity(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, SirType type, AstGenerationStrategy generation) {
   public NormalizedIdentity {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(generation, "generation");
   }
}
