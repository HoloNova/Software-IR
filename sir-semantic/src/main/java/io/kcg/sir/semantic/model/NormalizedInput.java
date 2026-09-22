package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@code input} declaration.
 *
 * <p>A {@link Kind#PATCH} input carries the identity of the entity it may change;
 * every one of its fields keeps the identity of the entity member it applies to,
 * so a later phase never has to match a change payload by name again.
 */
public record NormalizedInput(
   SymbolId id,
   String name,
   SourceSpan span,
   AstNodeId sourceNodeId,
   Kind kind,
   Optional<SymbolId> patchSourceEntity,
   List<NormalizedField> fields
) implements NormalizedDeclaration {
   public NormalizedInput {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(patchSourceEntity, "patchSourceEntity");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
   }

   public NormalizedInput(
      SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, List<NormalizedField> fields
   ) {
      this(id, name, span, sourceNodeId, Kind.PLAIN, Optional.empty(), fields);
   }

   public enum Kind {
      PLAIN,
      PATCH;
   }
}
