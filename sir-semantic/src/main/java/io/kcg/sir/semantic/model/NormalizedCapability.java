package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Identity;
import io.kcg.sir.semantic.schema.Keyed;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A capability. {@code method} is {@code put} or {@code delete} when the author chose one; {@code path} is the path under
 * {@code /api} the author chose ({@code /notes/{id}}), whose {@code {name}} segments are fields of the input.
 */
public record NormalizedCapability(
   @Identity SymbolId id,
   @Fact String name,
   @Source SourceSpan span,
   @Source AstNodeId sourceNodeId,
   @Reference(viaVariableType = true) Optional<SymbolId> actorSymbol,
   @Reference(viaVariableType = true) Optional<SymbolId> inputSymbol,
   @Fact SirType outputType,
   @Reference @Keyed List<SymbolId> fails,
   @Fact @Keyed List<AstRequirementKind> requires,
   @Fact AstExposureKind exposure,
   @Fact Optional<String> method,
   @Fact Optional<String> path,
   @Fact NormalizedWorkflow workflow
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
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(workflow, "workflow");
   }

   /** A capability whose method and path follow from its kind and name. */
   public NormalizedCapability(SymbolId id, String name, SourceSpan span, AstNodeId sourceNodeId, Optional<SymbolId> actorSymbol,
         Optional<SymbolId> inputSymbol, SirType outputType, List<SymbolId> fails, List<AstRequirementKind> requires,
         AstExposureKind exposure, NormalizedWorkflow workflow) {
      this(id, name, span, sourceNodeId, actorSymbol, inputSymbol, outputType, fails, requires, exposure, Optional.empty(), Optional.empty(), workflow);
   }
}
