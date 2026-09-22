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
   Optional<AstNodeId> directNamedTypeReferenceSiteId,
   boolean versioned,
   Optional<SymbolId> patchSourceField
) {
   public NormalizedField {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(type, "type");
      constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
      Objects.requireNonNull(directNamedTypeReferenceSiteId, "directNamedTypeReferenceSiteId");
      Objects.requireNonNull(patchSourceField, "patchSourceField");
   }

   public NormalizedField(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, SirType type, List<NormalizedConstraint> constraints, Optional<AstNodeId> directNamedTypeReferenceSiteId) {
      this(id, name, span, sourceNodeId, type, constraints, directNamedTypeReferenceSiteId, false, Optional.empty());
   }

   public NormalizedField(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, SirType type, List<NormalizedConstraint> constraints) {
      this(id, name, span, sourceNodeId, type, constraints, Optional.empty(), false, Optional.empty());
   }

   /**
   * A copy of this field carrying the bound entity field of a patch payload field.
   *
   * <p>Only patch input fields have one, and it is the sole reason a patch field can
   * be applied to an entity without ever matching it by name again.
   */
   public NormalizedField withPatchSourceField(SymbolId sourceField) {
      return new NormalizedField(this.id, this.name, this.span, this.sourceNodeId, this.type, this.constraints, this.directNamedTypeReferenceSiteId, this.versioned, Optional.of(sourceField));
   }

   public NormalizedField withVersioned(boolean marked) {
      return new NormalizedField(this.id, this.name, this.span, this.sourceNodeId, this.type, this.constraints, this.directNamedTypeReferenceSiteId, marked, this.patchSourceField);
   }
}
