package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeBaseRevision;
import java.nio.file.Path;
import java.util.Objects;

public record ChangeBaselineRegistrationRequest(Path baseSirFile, Path baselineSnapshotFile, Path outputRoot, Path stateRoot, ChangeBaseRevision basedOn) {
   public ChangeBaselineRegistrationRequest {
      Objects.requireNonNull(baseSirFile, "baseSirFile");
      Objects.requireNonNull(baselineSnapshotFile, "baselineSnapshotFile");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(basedOn, "basedOn");
   }
}
