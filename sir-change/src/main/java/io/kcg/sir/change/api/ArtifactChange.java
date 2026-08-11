package io.kcg.sir.change.api;

import java.util.List;
import java.util.Objects;

public record ArtifactChange(ImpactedArtifact artifact, List<FileChange> fileChanges) {
   public ArtifactChange {
      Objects.requireNonNull(artifact, "artifact");
      fileChanges = List.copyOf(Objects.requireNonNull(fileChanges, "fileChanges"));
      if (fileChanges.isEmpty()) {
         throw new IllegalArgumentException("ArtifactChange must own at least one FileChange: " + artifact.qualifiedName());
      }
   }
}
