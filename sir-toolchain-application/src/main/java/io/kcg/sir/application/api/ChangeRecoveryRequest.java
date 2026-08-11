package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.Objects;

public record ChangeRecoveryRequest(Path stateRoot, Path outputRoot, RecoveryHandle handle) {
   public ChangeRecoveryRequest {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(handle, "handle");
   }
}
