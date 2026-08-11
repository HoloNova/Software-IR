package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Objects;

public record ArtifactDeletion(LoweredNodeId artifactId, SymbolId ownerSymbol, DeclarationRole role, String qualifiedName, List<FileDeletion> fileDeletions) {
   public ArtifactDeletion {
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(qualifiedName, "qualifiedName");
      if (qualifiedName.isBlank()) {
         throw new IllegalArgumentException("qualifiedName must not be blank");
      }

      fileDeletions = List.copyOf(Objects.requireNonNull(fileDeletions, "fileDeletions"));
      if (fileDeletions.isEmpty()) {
         throw new IllegalArgumentException("ArtifactDeletion must own at least one FileDeletion: " + qualifiedName);
      }
   }
}
