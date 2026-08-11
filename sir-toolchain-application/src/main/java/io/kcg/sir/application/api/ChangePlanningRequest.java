package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeSet;
import java.nio.file.Path;
import java.util.Objects;

public record ChangePlanningRequest(Path baseSirFile, Path candidateSirFile, Path baselineSnapshotFile, Path outputRoot, ChangeSet changeSet) {
   public ChangePlanningRequest {
      Objects.requireNonNull(baseSirFile, "baseSirFile");
      Objects.requireNonNull(candidateSirFile, "candidateSirFile");
      Objects.requireNonNull(baselineSnapshotFile, "baselineSnapshotFile");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(changeSet, "changeSet");
   }
}
