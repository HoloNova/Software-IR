package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record NormalizedEntity(
   SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, boolean persistent, NormalizedIdentity identity, List<NormalizedField> fields
) implements NormalizedDeclaration {
   public NormalizedEntity {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(identity, "identity");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
   }
}
