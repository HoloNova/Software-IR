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
 * A {@code use} declaration, as the author wrote it: the feature's name and the arguments given to it.
 *
 * <p>What the feature expands into is in the model as ordinary declarations, each marked with this declaration as its
 * origin ({@code NormalizedSemanticModel.expandedFrom}). This declaration is what an edit changes and what the source
 * writer writes back; the expanded declarations are never written as source text.
 */
public record NormalizedFeatureUse(@Identity SymbolId id, @Fact String name, @Source SourceSpan span, @Source AstNodeId sourceNodeId,
      @Fact List<NormalizedFeatureArgument> arguments) implements NormalizedDeclaration {
   public NormalizedFeatureUse {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
   }
}
