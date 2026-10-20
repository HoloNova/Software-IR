package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

/**
 * Fields of an entity that no two rows may hold the same values of all together ({@code unique (a, b);}), in the order
 * the author wrote them, which is the order of the index.
 */
public record NormalizedUniqueKey(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference List<SymbolId> fields) {
   public NormalizedUniqueKey {
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(span, "span");
      fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
   }
}
