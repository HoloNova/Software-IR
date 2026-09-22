package io.kcg.sir.lowering.springboot.model;

import java.util.Objects;

public record TransportPlan(TransportPlan.InputBinding inputBinding, TransportPlan.ResponseRepresentation responseRepresentation) {
   public TransportPlan {
      Objects.requireNonNull(inputBinding, "inputBinding");
      Objects.requireNonNull(responseRepresentation, "responseRepresentation");
   }

   public enum InputBinding {
      NONE,
      REQUEST_BODY,
      MODEL_ATTRIBUTE;
   }

   public enum ResponseRepresentation {
      VOID,
      VALUE,
      ENTITY_BODY,
      LIST,
      OPTIONAL,
      PAGE,
      PROJECTION;
   }
}
