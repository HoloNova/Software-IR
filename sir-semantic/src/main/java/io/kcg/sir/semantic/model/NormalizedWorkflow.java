package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record NormalizedWorkflow(SymbolId id, SourceSpan span, AstNodeId sourceNodeId, List<NormalizedStep> steps) {
   public NormalizedWorkflow {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
   }
}
