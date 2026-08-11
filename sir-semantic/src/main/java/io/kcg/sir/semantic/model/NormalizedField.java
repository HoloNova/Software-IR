package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NormalizedField(
   SymbolId id,
   String name,
   SourceSpan span,
   AstNodeId sourceNodeId,
   SirType type,
   List<NormalizedConstraint> constraints,
   Optional<AstNodeId> directNamedTypeReferenceSiteId
) {
   public NormalizedField {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(type, "type");
      constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
      Objects.requireNonNull(directNamedTypeReferenceSiteId, "directNamedTypeReferenceSiteId");
   }

   public NormalizedField(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, SirType type, List<NormalizedConstraint> constraints) {
      this(id, name, span, sourceNodeId, type, constraints, Optional.empty());
   }
}
