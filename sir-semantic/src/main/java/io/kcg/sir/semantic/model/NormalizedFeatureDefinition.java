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
 * A {@code feature} definition, as the author wrote it: the parameters and the text a {@code use} of the feature expands
 * into. A feature adds no declaration of its own; what it expands into is in the model as ordinary declarations.
 */
public record NormalizedFeatureDefinition(@Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId,
      @Fact List<NormalizedFeatureParameter> parameters, @Fact String template) implements NormalizedDeclaration {
   public NormalizedFeatureDefinition {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
      Objects.requireNonNull(template, "template");
   }
}
