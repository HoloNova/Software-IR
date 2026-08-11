package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record NormalizedInput(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, List<NormalizedField> fields) implements NormalizedDeclaration {
   public NormalizedInput {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
   }
}
