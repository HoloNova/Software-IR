package io.kcg.sir.application.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ExecutionManifest(Path outputRoot, ConflictPolicy conflictPolicy, List<AppliedFile> files) {
   public ExecutionManifest {
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(conflictPolicy, "conflictPolicy");
      if (!outputRoot.isAbsolute()) {
         throw new IllegalArgumentException("outputRoot must be absolute");
      }

      outputRoot = outputRoot.normalize();
      files = List.copyOf(Objects.requireNonNull(files, "files"));
   }
}
