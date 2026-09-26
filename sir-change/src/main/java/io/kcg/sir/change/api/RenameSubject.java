package io.kcg.sir.change.api;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

/**
 * The declaration a rename plan is about: the same persistent identity on both sides, under two
 * names.
 *
 * <p>The identity is what proves this is one renamed declaration rather than a deletion plus a
 * creation; the names are what the rename changes, and they must differ.
 */
public record RenameSubject(SymbolId declarationSymbol, RenameSubjectKind kind, String baseName, String candidateName) {
   public RenameSubject {
      Objects.requireNonNull(declarationSymbol, "declarationSymbol");
      Objects.requireNonNull(kind, "kind");
      if (baseName == null || baseName.isBlank()) {
         throw new IllegalArgumentException("baseName must not be blank");
      }

      if (candidateName == null || candidateName.isBlank()) {
         throw new IllegalArgumentException("candidateName must not be blank");
      }

      if (baseName.equals(candidateName)) {
         throw new IllegalArgumentException("a rename subject must have two different names: " + baseName);
      }
   }
}
