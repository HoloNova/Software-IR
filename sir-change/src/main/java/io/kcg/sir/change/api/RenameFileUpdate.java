package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

/**
 * A managed file that exists on both sides of the rename and whose content is replaced in place.
 *
 * <p>Both digests are carried: the base pair is what an apply must still find on disk, the candidate
 * pair is what it must write. A path whose two digests happen to be equal is still an update — the
 * plan describes which managed paths the rename touches, and the digests say what that touch costs.
 */
public record RenameFileUpdate(
   String relativePath,
   LoweredNodeId artifactId,
   Optional<SymbolId> ownerSymbol,
   long baseByteCount,
   String baseSha256Hex,
   long candidateByteCount,
   String candidateSha256Hex
) {
   public RenameFileUpdate {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(artifactId, "artifactId");
      ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      requireByteCount(baseByteCount);
      requireByteCount(candidateByteCount);
      requireDigest(baseSha256Hex, "baseSha256Hex");
      requireDigest(candidateSha256Hex, "candidateSha256Hex");
   }

   private static void requireByteCount(long byteCount) {
      if (byteCount < 0L) {
         throw new IllegalArgumentException("byteCount must not be negative");
      }
   }

   private static void requireDigest(String digest, String name) {
      if (digest == null || !digest.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException(name + " must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
