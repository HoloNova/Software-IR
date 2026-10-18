package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * An {@code extend} declaration, as the author wrote it: the fields it adds to {@code entity}. The fields belong to
 * the entity (their identities are the entity's fields'), but the model lists them here, where they are written, and
 * not in the entity; a target implements the entity with them added.
 */
public record NormalizedEntityExtension(@Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId,
      @Reference SymbolId entity, @Fact List<NormalizedField> fields) implements NormalizedDeclaration {
   public NormalizedEntityExtension {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(entity, "entity");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
   }
}
