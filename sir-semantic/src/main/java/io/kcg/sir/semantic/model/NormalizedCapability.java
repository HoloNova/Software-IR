package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NormalizedCapability(
   SymbolId id,
   String name,
   SourceSpan span,
   AstNodeId sourceNodeId,
   Optional<SymbolId> actorSymbol,
   Optional<SymbolId> inputSymbol,
   SirType outputType,
   List<SymbolId> fails,
   List<AstRequirementKind> requires,
   AstExposureKind exposure,
   NormalizedWorkflow workflow
) implements NormalizedDeclaration {
   public NormalizedCapability {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(span, "span");
      Objects.requireNonNull(sourceNodeId, "sourceNodeId");
      Objects.requireNonNull(actorSymbol, "actorSymbol");
      Objects.requireNonNull(inputSymbol, "inputSymbol");
      Objects.requireNonNull(outputType, "outputType");
      fails = List.copyOf(Objects.requireNonNull(fails, "fails"));
      requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
      Objects.requireNonNull(exposure, "exposure");
      Objects.requireNonNull(workflow, "workflow");
   }
}
