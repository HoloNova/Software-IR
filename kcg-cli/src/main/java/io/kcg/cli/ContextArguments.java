package io.kcg.cli;

import java.nio.file.Path;
import java.util.Objects;

public record ContextArguments(Path stateRoot, Path outputRoot, Path candidateSirFile) {
   public ContextArguments {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(candidateSirFile, "candidateSirFile");
   }
}
