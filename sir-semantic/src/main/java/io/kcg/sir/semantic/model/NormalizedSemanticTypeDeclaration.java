package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * A {@code type} declaration, as the author wrote it: the semantic type it is based on, and the rules written on it.
 * Fields that use the type carry the type's name; the rules the type brings reach them as ordinary constraints.
 */
public record NormalizedSemanticTypeDeclaration(@Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId,
      @Fact String base, @Fact List<NormalizedConstraint> constraints) implements NormalizedDeclaration {
   public NormalizedSemanticTypeDeclaration {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(base, "base");
      constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
   }
}
