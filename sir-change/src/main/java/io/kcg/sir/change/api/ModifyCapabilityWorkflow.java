package io.kcg.sir.change.api;

import java.util.Objects;

public record ModifyCapabilityWorkflow(ChangeTarget target) implements ChangeOperation {
   public ModifyCapabilityWorkflow {
      Objects.requireNonNull(target, "target");
   }
}
