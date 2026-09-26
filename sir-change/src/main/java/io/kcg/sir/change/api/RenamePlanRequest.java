package io.kcg.sir.change.api;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

/**
 * Which declaration to plan a rename for.
 *
 * <p>{@code declarationSymbol} must be an explicit persistent identity (a declaration that wrote
 * {@code @id("...")}). A name-derived identity cannot prove that two names mean one declaration, and
 * the planner will not guess from text similarity, so such a request is rejected instead of
 * interpreted.
 */
public record RenamePlanRequest(ChangeBaseRevision basedOn, SymbolId declarationSymbol) {
   public RenamePlanRequest {
      Objects.requireNonNull(basedOn, "basedOn");
      Objects.requireNonNull(declarationSymbol, "declarationSymbol");
   }
}
