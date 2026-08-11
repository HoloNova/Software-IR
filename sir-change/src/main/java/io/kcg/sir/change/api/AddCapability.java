package io.kcg.sir.change.api;

import java.util.Objects;

public record AddCapability(ChangeTarget target) implements ChangeOperation {
   public AddCapability {
      Objects.requireNonNull(target, "target");
   }
}
