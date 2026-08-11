package io.kcg.sir.lowering.springboot.model;

import java.util.List;
import java.util.Objects;

public record ActorIdentityTransportPlan(
   ActorIdentityTransportPlan.Policy policy,
   String modeProperty,
   String externalAdapterBeanName,
   String localProfile,
   String localIdentityProperty,
   List<ActorIdentityRequirement> requirements
) {
   public static final String MODE_PROPERTY = "kcg.actor-identity.mode";
   public static final String EXTERNAL_ADAPTER_BEAN_NAME = "kcgActorIdentityTransport";
   public static final String LOCAL_PROFILE = "kcg-actor-local";
   public static final String LOCAL_IDENTITY_PROPERTY = "kcg.actor-identity.local.id";

   public ActorIdentityTransportPlan {
      Objects.requireNonNull(policy, "policy");
      if (policy != ActorIdentityTransportPlan.Policy.EXPLICIT_EXTERNAL_OR_LOCAL_FIXED) {
         throw new IllegalArgumentException("unsupported policy: " + policy);
      }

      Objects.requireNonNull(modeProperty, "modeProperty");
      if (!modeProperty.equals("kcg.actor-identity.mode")) {
         throw new IllegalArgumentException("modeProperty must be 'kcg.actor-identity.mode': " + modeProperty);
      }

      Objects.requireNonNull(externalAdapterBeanName, "externalAdapterBeanName");
      if (!externalAdapterBeanName.equals("kcgActorIdentityTransport")) {
         throw new IllegalArgumentException("externalAdapterBeanName must be 'kcgActorIdentityTransport': " + externalAdapterBeanName);
      }

      Objects.requireNonNull(localProfile, "localProfile");
      if (!localProfile.equals("kcg-actor-local")) {
         throw new IllegalArgumentException("localProfile must be 'kcg-actor-local': " + localProfile);
      }

      Objects.requireNonNull(localIdentityProperty, "localIdentityProperty");
      if (!localIdentityProperty.equals("kcg.actor-identity.local.id")) {
         throw new IllegalArgumentException("localIdentityProperty must be 'kcg.actor-identity.local.id': " + localIdentityProperty);
      }

      Objects.requireNonNull(requirements, "requirements");
      if (requirements.isEmpty()) {
         throw new IllegalArgumentException(
            "ActorIdentityTransportPlan requires at least one requirement; use Optional.empty() when no actor capability exists"
         );
      }

      requirements = List.copyOf(requirements);
   }

   public enum Policy {
      EXPLICIT_EXTERNAL_OR_LOCAL_FIXED;
   }
}
