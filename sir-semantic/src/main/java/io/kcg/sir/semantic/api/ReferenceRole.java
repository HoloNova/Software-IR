package io.kcg.sir.semantic.api;

import io.kcg.sir.semantic.symbol.SymbolKind;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ReferenceRole {
   NAMED_TYPE(SymbolKind.PRIMITIVE, SymbolKind.ENUM, SymbolKind.ENTITY, SymbolKind.INPUT),
   REF_TYPE_TARGET(SymbolKind.ENTITY),
   CAPABILITY_FAILS_ERROR(SymbolKind.ERROR),
   VALIDATE_ERROR(SymbolKind.ERROR),
   LOAD_ERROR(SymbolKind.ERROR),
   LOAD_ENTITY(SymbolKind.ENTITY),
   FIND_ENTITY(SymbolKind.ENTITY),
   CREATE_ENTITY(SymbolKind.ENTITY),
   UPDATE_TARGET(SymbolKind.VARIABLE),
   PERSIST_TARGET(SymbolKind.VARIABLE),
   BINDING_FIELD(SymbolKind.FIELD),
   EXPRESSION_NAME(SymbolKind.VARIABLE, SymbolKind.ENUM),
   MEMBER_ACCESS(SymbolKind.FIELD, SymbolKind.ENUM_MEMBER);

   private final Set<SymbolKind> allowedTargetKinds;

   ReferenceRole(SymbolKind... kinds) {
      Set<SymbolKind> set = EnumSet.noneOf(SymbolKind.class);

      for (SymbolKind kind : kinds) {
         set.add(kind);
      }

      this.allowedTargetKinds = Collections.unmodifiableSet(EnumSet.copyOf(set));
   }

   public Set<SymbolKind> allowedTargetKinds() {
      return this.allowedTargetKinds;
   }

   public boolean accepts(SymbolKind kind) {
      return kind != null && this.allowedTargetKinds.contains(kind);
   }
}
