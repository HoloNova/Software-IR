package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

public record NormalizedConstraint(AstNodeId sourceNodeId, SourceSpan span, String name, List<NormalizedExpression> arguments) {
   public NormalizedConstraint {
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(name, "name");
      arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
   }
}
