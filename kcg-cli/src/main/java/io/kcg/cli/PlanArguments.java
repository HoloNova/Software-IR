package io.kcg.cli;

import io.kcg.sir.change.api.ChangeIrVersion;
import java.nio.file.Path;
import java.util.Objects;

public record PlanArguments(
   Path stateRoot,
   Path outputRoot,
   Path candidateSirFile,
   String expectedContextId,
   String targetKey,
   ChangeIrVersion changeIrVersion,
   OperationToken operation
) {
   public PlanArguments {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(candidateSirFile, "candidateSirFile");
      Objects.requireNonNull(expectedContextId, "expectedContextId");
      Objects.requireNonNull(targetKey, "targetKey");
      Objects.requireNonNull(changeIrVersion, "changeIrVersion");
      Objects.requireNonNull(operation, "operation");
   }
}
