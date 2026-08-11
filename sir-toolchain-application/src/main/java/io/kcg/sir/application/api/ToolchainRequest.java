package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

public record ToolchainRequest(Path sourceFile, SourceId sourceId, Path outputRoot, ConflictPolicy conflictPolicy) {
   public ToolchainRequest {
      Objects.requireNonNull(sourceFile, "sourceFile");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(conflictPolicy, "conflictPolicy");
   }
}
