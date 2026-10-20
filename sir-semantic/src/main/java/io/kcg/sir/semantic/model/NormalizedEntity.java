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

/**
 * A persistent entity. {@code scope} is the condition a row must meet to exist for the software: every load, find
 * and existence test of the entity sees only rows it holds for; inside it a bare name is a field of the entity.
 * {@code uniqueKeys} are the combinations of fields no two rows may share.
 */
public record NormalizedEntity(
   @Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId, @Fact boolean persistent, @Fact NormalizedIdentity identity, @Fact List<NormalizedField> fields, @Derived Optional<SymbolId> versionField, @Fact Optional<NormalizedCredential> credential,
   @Fact Optional<NormalizedExpression> scope, @Fact List<NormalizedUniqueKey> uniqueKeys
) implements NormalizedDeclaration {
   public NormalizedEntity(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, boolean persistent, NormalizedIdentity identity, List<NormalizedField> fields, Optional<SymbolId> versionField) {
      this(id, name, span, sourceNodeId, persistent, identity, fields, versionField, Optional.empty(), Optional.empty(), List.of());
   }

   public NormalizedEntity(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, boolean persistent, NormalizedIdentity identity, List<NormalizedField> fields, Optional<SymbolId> versionField, Optional<NormalizedCredential> credential, Optional<NormalizedExpression> scope) {
      this(id, name, span, sourceNodeId, persistent, identity, fields, versionField, credential, scope, List.of());
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
      Objects.requireNonNull(scope, "scope");
      uniqueKeys = List.copyOf(Objects.requireNonNull(uniqueKeys, "uniqueKeys"));
   }
}
