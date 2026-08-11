package io.kcg.sir.change.api;

import java.util.Objects;

public record ModifyInputFieldConstraints(ChangeTarget target) implements ChangeOperation {
   public ModifyInputFieldConstraints {
      Objects.requireNonNull(target, "target");
   }
}
