package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record FileAddition(String relativePath, LoweredNodeId artifactId, SymbolId ownerSymbol, long candidateByteCount, String candidateSha256Hex) {
   public FileAddition {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(candidateSha256Hex, "candidateSha256Hex");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      if (candidateByteCount < 0L) {
         throw new IllegalArgumentException("candidateByteCount must not be negative");
      }

      if (!candidateSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("candidateSha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
