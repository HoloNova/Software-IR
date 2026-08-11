package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.Objects;

public record ChangePlanningContextRequest(Path stateRoot, Path outputRoot, Path candidateSirFile) {
   public ChangePlanningContextRequest {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(candidateSirFile, "candidateSirFile");
   }
}
