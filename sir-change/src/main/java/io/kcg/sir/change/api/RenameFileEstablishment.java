package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

/**
 * A managed file the rename creates at a path the base manifest does not hold.
 *
 * <p>The plan asserts the path is free in the base manifest, not on disk: whether an untracked file
 * already occupies it is a precondition the apply unit has to check before writing.
 */
public record RenameFileEstablishment(
   String relativePath,
   LoweredNodeId artifactId,
   Optional<SymbolId> ownerSymbol,
   long byteCount,
   String sha256Hex
) {
   public RenameFileEstablishment {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(artifactId, "artifactId");
      ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      if (byteCount < 0L) {
         throw new IllegalArgumentException("byteCount must not be negative");
      }

      if (sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
