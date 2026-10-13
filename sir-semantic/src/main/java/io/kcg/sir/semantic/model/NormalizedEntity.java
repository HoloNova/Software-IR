package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NormalizedEntity(
   @Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId, @Fact boolean persistent, @Fact NormalizedIdentity identity, @Fact List<NormalizedField> fields, @Reference Optional<SymbolId> versionField
) implements NormalizedDeclaration {
   public NormalizedEntity {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(identity, "identity");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
      Objects.requireNonNull(versionField, "versionField");
   }
}
