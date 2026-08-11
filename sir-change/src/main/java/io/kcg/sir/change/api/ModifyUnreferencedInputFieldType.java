package io.kcg.sir.change.api;

import java.util.Objects;

public record ModifyUnreferencedInputFieldType(ChangeTarget target) implements ChangeOperation {
   public ModifyUnreferencedInputFieldType {
      Objects.requireNonNull(target, "target");
   }
}
