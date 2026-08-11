package io.kcg.sir.semantic.api;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstMetadata;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.SirType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record NormalizedSemanticModel(
   String softwareName,
   AstMetadata metadata,
   AstTarget target,
   SymbolTable symbols,
   Map<AstNodeId, SymbolId> referenceBindings,
   Map<AstNodeId, SymbolId> findItemBindings,
   Map<AstNodeId, SirType> expressionTypes,
   List<NormalizedDeclaration> declarations,
   List<Diagnostic> diagnostics,
   ReferenceSiteBindings referenceSiteBindings
) {
   public NormalizedSemanticModel {
      Objects.requireNonNull(softwareName, "softwareName");
      Objects.requireNonNull(metadata, "metadata");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(symbols, "symbols");
      referenceBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(referenceBindings, "referenceBindings")));
      findItemBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(findItemBindings, "findItemBindings")));
      expressionTypes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(expressionTypes, "expressionTypes")));
      declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      Objects.requireNonNull(referenceSiteBindings, "referenceSiteBindings");
   }

   public NormalizedSemanticModel(
      String softwareName,
      AstMetadata metadata,
      AstTarget target,
      SymbolTable symbols,
      Map<AstNodeId, SymbolId> referenceBindings,
      Map<AstNodeId, SymbolId> findItemBindings,
      Map<AstNodeId, SirType> expressionTypes,
      List<NormalizedDeclaration> declarations,
      List<Diagnostic> diagnostics
   ) {
      this(
         softwareName,
         metadata,
         target,
         symbols,
         referenceBindings,
         findItemBindings,
         expressionTypes,
         declarations,
         diagnostics,
         ReferenceSiteBindings.empty()
      );
   }
}
