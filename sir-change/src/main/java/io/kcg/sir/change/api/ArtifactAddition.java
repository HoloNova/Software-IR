package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Objects;

public record ArtifactAddition(LoweredNodeId artifactId, SymbolId ownerSymbol, DeclarationRole role, String qualifiedName, List<FileAddition> fileAdditions) {
   public ArtifactAddition {
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(qualifiedName, "qualifiedName");
      if (qualifiedName.isBlank()) {
         throw new IllegalArgumentException("qualifiedName must not be blank");
      }

      fileAdditions = List.copyOf(Objects.requireNonNull(fileAdditions, "fileAdditions"));
      if (fileAdditions.isEmpty()) {
         throw new IllegalArgumentException("ArtifactAddition must own at least one FileAddition: " + qualifiedName);
      }
   }
}
