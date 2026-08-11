package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record ActorIdentityRequirement(
   SymbolId capabilitySymbol,
   SymbolId actorEntitySymbol,
   String actorEntityJavaName,
   SpringBootDeclaration.HttpMethod httpMethod,
   String route,
   String attributeName,
   LoweredJavaType.Scalar identityStorageType
) {
   public ActorIdentityRequirement {
      Objects.requireNonNull(capabilitySymbol, "capabilitySymbol");
      Objects.requireNonNull(actorEntitySymbol, "actorEntitySymbol");
      Objects.requireNonNull(actorEntityJavaName, "actorEntityJavaName");
      if (actorEntityJavaName.isBlank()) {
         throw new IllegalArgumentException("actorEntityJavaName must not be blank");
      }

      Objects.requireNonNull(httpMethod, "httpMethod");
      Objects.requireNonNull(route, "route");
      if (route.isBlank()) {
         throw new IllegalArgumentException("route must not be blank");
      }

      Objects.requireNonNull(attributeName, "attributeName");
      if (attributeName.isBlank()) {
         throw new IllegalArgumentException("attributeName must not be blank");
      }

      Objects.requireNonNull(identityStorageType, "identityStorageType");
   }
}
