package io.kcg.sir.change.api;

import java.util.Objects;

public record ModifyActorlessReadonlyCapabilityExposure(ChangeTarget target) implements ChangeOperation {
   public ModifyActorlessReadonlyCapabilityExposure {
      Objects.requireNonNull(target, "target");
   }
}
