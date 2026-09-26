package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

/**
 * A managed file that the rename retires: it exists only in the base closure, and the plan proves the
 * renamed declaration's own artifacts are its only generators.
 *
 * <p>This plan never deletes anything; the pair is the precondition an apply must still find, not an
 * instruction that has already run.
 */
public record RenameFileWithdrawal(
   String relativePath,
   LoweredNodeId artifactId,
   Optional<SymbolId> ownerSymbol,
   long byteCount,
   String sha256Hex
) {
   public RenameFileWithdrawal {
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
