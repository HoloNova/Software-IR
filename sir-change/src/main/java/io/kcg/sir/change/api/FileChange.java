package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

public record FileChange(
   String relativePath,
   LoweredNodeId artifactId,
   Optional<SymbolId> ownerSymbol,
   long baseByteCount,
   String baseSha256Hex,
   long candidateByteCount,
   String candidateSha256Hex
) {
   public FileChange {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(artifactId, "artifactId");
      ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(baseSha256Hex, "baseSha256Hex");
      Objects.requireNonNull(candidateSha256Hex, "candidateSha256Hex");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      if (baseByteCount < 0L) {
         throw new IllegalArgumentException("baseByteCount must not be negative");
      }

      if (candidateByteCount < 0L) {
         throw new IllegalArgumentException("candidateByteCount must not be negative");
      }

      if (!baseSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("baseSha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }

      if (!candidateSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("candidateSha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }

      if (baseSha256Hex.equals(candidateSha256Hex) && baseByteCount != candidateByteCount) {
         throw new IllegalArgumentException("sha256 equal but byteCount differs: " + relativePath);
      }
   }

   public boolean bytesChanged() {
      return !this.baseSha256Hex.equals(this.candidateSha256Hex);
   }
}
