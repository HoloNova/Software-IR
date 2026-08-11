package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

public record GeneratedBaselineRegistrationRequest(Path baseSirFile, SourceId sourceId, Path outputRoot, Path stateRoot) {
   public GeneratedBaselineRegistrationRequest {
      Objects.requireNonNull(baseSirFile, "baseSirFile");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(stateRoot, "stateRoot");
   }
}
