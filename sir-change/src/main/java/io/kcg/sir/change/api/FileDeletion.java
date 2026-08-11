package io.kcg.sir.change.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public record FileDeletion(String relativePath, LoweredNodeId artifactId, SymbolId ownerSymbol, long baseByteCount, String baseSha256Hex) {
   public FileDeletion {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      Objects.requireNonNull(baseSha256Hex, "baseSha256Hex");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      if (baseByteCount < 0L) {
         throw new IllegalArgumentException("baseByteCount must not be negative");
      }

      if (!baseSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("baseSha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
