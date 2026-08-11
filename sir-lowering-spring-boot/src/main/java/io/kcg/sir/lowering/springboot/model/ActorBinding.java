package io.kcg.sir.lowering.springboot.model;

import java.util.Objects;

public record ActorBinding(ActorBinding.ActorKind kind, String attributeName, LoweredJavaType.Scalar identityStorageType) {
   public static final String DEFAULT_ATTRIBUTE_NAME = "actorId";

   public ActorBinding {
      Objects.requireNonNull(kind, "kind");
      if (kind != ActorBinding.ActorKind.REQUEST_ATTRIBUTE) {
         throw new IllegalArgumentException("unsupported actor kind: " + kind);
      }

      Objects.requireNonNull(attributeName, "attributeName");
      if (!attributeName.equals("actorId")) {
         throw new IllegalArgumentException("attributeName must be 'actorId'");
      }

      Objects.requireNonNull(identityStorageType, "identityStorageType");
   }

   public enum ActorKind {
      REQUEST_ATTRIBUTE;
   }
}
