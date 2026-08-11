package io.kcg.sir.application.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

public record AppliedFile(String relativePath, FileAction action, long byteCount, String sha256Hex, LoweredNodeId artifactId, Optional<SymbolId> symbolId) {
   public AppliedFile {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(sha256Hex, "sha256Hex");
      Objects.requireNonNull(artifactId, "artifactId");
      symbolId = Objects.requireNonNull(symbolId, "symbolId");
      if (relativePath.isBlank()) {
         throw new IllegalArgumentException("relativePath must not be blank");
      }

      if (byteCount < 0L) {
         throw new IllegalArgumentException("byteCount must not be negative");
      }

      if (!sha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
