package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record SpringArtifact(LoweredNodeId id, LoweredOrigin origin, SymbolId ownerSymbol, SpringArtifact.Role role, String packageName, String simpleName) {
   public SpringArtifact {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(role, "role");
      requireText(packageName, "packageName");
      requireText(simpleName, "simpleName");
   }

   public String qualifiedName() {
      return this.packageName + "." + this.simpleName;
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   public enum Role {
      ENUM,
      ENTITY_MODEL,
      MAPPER,
      REQUEST_DTO,
      EXCEPTION,
      SERVICE,
      CONTROLLER;
   }
}
