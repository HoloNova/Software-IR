package io.kcg.sir.application.internal.bundle;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

public record BaselineManifestEntry(String relativePath, long byteCount, String sha256Hex, LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol) {
   public BaselineManifestEntry {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(sha256Hex, "sha256Hex");
      Objects.requireNonNull(artifactId, "artifactId");
      ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      if (relativePath.indexOf(10) >= 0 || relativePath.indexOf(13) >= 0) {
         throw new IllegalArgumentException("relativePath must not contain newlines");
      }

      if (byteCount < 0L) {
         throw new IllegalArgumentException("byteCount must not be negative");
      }

      if (!sha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
