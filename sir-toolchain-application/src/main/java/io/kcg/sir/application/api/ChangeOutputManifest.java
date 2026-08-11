package io.kcg.sir.application.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ChangeOutputManifest(List<ChangeOutputManifest.Entry> entries) {
   public ChangeOutputManifest {
      entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
   }

   public record Entry(String relativePath, long byteCount, String sha256Hex, LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol) {
      public Entry {
         Objects.requireNonNull(relativePath, "relativePath");
         Objects.requireNonNull(sha256Hex, "sha256Hex");
         Objects.requireNonNull(artifactId, "artifactId");
         ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
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
}
