package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;

public sealed interface NormalizedDeclaration permits NormalizedEnum, NormalizedEntity, NormalizedInput, NormalizedView, NormalizedError, NormalizedCapability {
   SymbolId id();

   String name();

   SourceSpan span();

   AstNodeId sourceNodeId();
}
