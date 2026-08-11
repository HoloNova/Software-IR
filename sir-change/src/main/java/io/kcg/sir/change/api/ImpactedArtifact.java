package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ImpactedArtifact(LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol, ArtifactRole role, String qualifiedName, List<FileChange> fileChanges) {
   public ImpactedArtifact {
      Objects.requireNonNull(artifactId, "artifactId");
      ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(qualifiedName, "qualifiedName");
      if (qualifiedName.isBlank()) {
         throw new IllegalArgumentException("qualifiedName must not be blank");
      }

      fileChanges = List.copyOf(Objects.requireNonNull(fileChanges, "fileChanges"));
      if (fileChanges.isEmpty()) {
         throw new IllegalArgumentException("ImpactedArtifact must own at least one FileChange: " + qualifiedName);
      }
   }
}
