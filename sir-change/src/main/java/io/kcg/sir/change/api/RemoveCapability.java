package io.kcg.sir.change.api;

import java.util.Objects;

public record RemoveCapability(ChangeTarget target) implements ChangeOperation {
   public RemoveCapability {
      Objects.requireNonNull(target, "target");
   }
}
