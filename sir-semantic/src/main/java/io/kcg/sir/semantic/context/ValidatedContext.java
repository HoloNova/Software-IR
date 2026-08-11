package io.kcg.sir.semantic.context;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.api.ReferenceSiteBindings;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.SirType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ValidatedContext(
   String softwareName,
   SymbolTable symbols,
   Map<AstNodeId, SymbolId> referenceBindings,
   Map<AstNodeId, SirType> typeRefTypes,
   Map<AstNodeId, SymbolId> findItemBindings,
   ReferenceSiteBindings referenceSiteBindings,
   Map<AstNodeId, SymbolId> declarationBindings,
   Map<AstNodeId, SirType> expressionTypes,
   List<Diagnostic> diagnostics
) {
   public ValidatedContext {
      Objects.requireNonNull(softwareName, "softwareName");
      Objects.requireNonNull(symbols, "symbols");
      referenceBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(referenceBindings, "referenceBindings")));
      typeRefTypes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(typeRefTypes, "typeRefTypes")));
      findItemBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(findItemBindings, "findItemBindings")));
      Objects.requireNonNull(referenceSiteBindings, "referenceSiteBindings");
      declarationBindings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(declarationBindings, "declarationBindings")));
      expressionTypes = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(expressionTypes, "expressionTypes")));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
   }

   public static ValidatedContext from(TypedContext typed, List<Diagnostic> diagnostics) {
      List<Diagnostic> combined = new ArrayList<>(typed.diagnostics());
      combined.addAll(diagnostics);
      return new ValidatedContext(
         typed.softwareName(),
         typed.symbols(),
         typed.referenceBindings(),
         typed.typeRefTypes(),
         typed.findItemBindings(),
         typed.referenceSiteBindings(),
         typed.declarationBindings(),
         typed.expressionTypes(),
         combined
      );
   }
}
