package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.change.api.FileDeletion;
import java.util.Objects;

public final class DeleteFilePayload {
   private final FileDeletion deletion;
   private final BaselineManifestEntry b0Entry;

   public DeleteFilePayload(FileDeletion deletion, BaselineManifestEntry b0Entry) {
      this.deletion = Objects.requireNonNull(deletion, "deletion");
      this.b0Entry = Objects.requireNonNull(b0Entry, "b0Entry");
      if (!deletion.relativePath().equals(b0Entry.relativePath())) {
         throw new IllegalArgumentException("DeleteFilePayload path mismatch: deletion=" + deletion.relativePath() + " b0=" + b0Entry.relativePath());
      }

      if (!deletion.artifactId().equals(b0Entry.artifactId())) {
         throw new IllegalArgumentException(
            "DeleteFilePayload artifactId mismatch for " + deletion.relativePath() + ": deletion=" + deletion.artifactId() + " b0=" + b0Entry.artifactId()
         );
      }

      if (b0Entry.ownerSymbol().isEmpty() || !deletion.ownerSymbol().equals(b0Entry.ownerSymbol().get())) {
         throw new IllegalArgumentException(
            "DeleteFilePayload ownerSymbol mismatch for " + deletion.relativePath() + ": deletion=" + deletion.ownerSymbol() + " b0=" + b0Entry.ownerSymbol()
         );
      }

      if (deletion.baseByteCount() != b0Entry.byteCount()) {
         throw new IllegalArgumentException(
            "DeleteFilePayload base byte count mismatch for "
               + deletion.relativePath()
               + ": deletion="
               + deletion.baseByteCount()
               + " b0="
               + b0Entry.byteCount()
         );
      }

      if (!deletion.baseSha256Hex().equals(b0Entry.sha256Hex())) {
         throw new IllegalArgumentException(
            "DeleteFilePayload base SHA-256 mismatch for " + deletion.relativePath() + ": deletion=" + deletion.baseSha256Hex() + " b0=" + b0Entry.sha256Hex()
         );
      }
   }

   public String relativePath() {
      return this.deletion.relativePath();
   }

   public FileDeletion deletion() {
      return this.deletion;
   }

   public BaselineManifestEntry b0Entry() {
      return this.b0Entry;
   }

   public long byteCount() {
      return this.b0Entry.byteCount();
   }

   public String sha256Hex() {
      return this.b0Entry.sha256Hex();
   }

   @Override
   public String toString() {
      return "DeleteFilePayload{rel=" + this.deletion.relativePath() + ", bytes=" + this.b0Entry.byteCount() + "}";
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else {
         return !(o instanceof DeleteFilePayload other) ? false : this.deletion.equals(other.deletion) && this.b0Entry.equals(other.b0Entry);
      }
   }

   @Override
   public int hashCode() {
      int result = this.deletion.hashCode();
      return 31 * result + this.b0Entry.hashCode();
   }
}
