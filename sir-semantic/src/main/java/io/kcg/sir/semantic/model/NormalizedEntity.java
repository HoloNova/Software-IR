package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NormalizedEntity(
   @Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId, @Fact boolean persistent, @Fact NormalizedIdentity identity, @Fact List<NormalizedField> fields, @Derived Optional<SymbolId> versionField, @Fact Optional<NormalizedCredential> credential
) implements NormalizedDeclaration {
   public NormalizedEntity(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, boolean persistent, NormalizedIdentity identity, List<NormalizedField> fields, Optional<SymbolId> versionField) {
      this(id, name, span, sourceNodeId, persistent, identity, fields, versionField, Optional.empty());
   }

   public NormalizedEntity {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(identity, "identity");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
      Objects.requireNonNull(versionField, "versionField");
      Objects.requireNonNull(credential, "credential");
   }
}
