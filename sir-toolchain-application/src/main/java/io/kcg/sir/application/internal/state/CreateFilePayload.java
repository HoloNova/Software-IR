package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.change.api.FileAddition;
import java.util.Arrays;
import java.util.Objects;

public final class CreateFilePayload {
   private final FileAddition addition;
   private final byte[] stagedBytes;
   private final BaselineManifestEntry b1Entry;

   public CreateFilePayload(FileAddition addition, byte[] stagedBytes, BaselineManifestEntry b1Entry) {
      this.addition = Objects.requireNonNull(addition, "addition");
      this.b1Entry = Objects.requireNonNull(b1Entry, "b1Entry");
      Objects.requireNonNull(stagedBytes, "stagedBytes");
      if (!addition.relativePath().equals(b1Entry.relativePath())) {
         throw new IllegalArgumentException("CreateFilePayload path mismatch: addition=" + addition.relativePath() + " b1=" + b1Entry.relativePath());
      }

      if (!addition.artifactId().equals(b1Entry.artifactId())) {
         throw new IllegalArgumentException(
            "CreateFilePayload artifactId mismatch for " + addition.relativePath() + ": addition=" + addition.artifactId() + " b1=" + b1Entry.artifactId()
         );
      }

      if (b1Entry.ownerSymbol().isEmpty() || !addition.ownerSymbol().equals(b1Entry.ownerSymbol().get())) {
         throw new IllegalArgumentException(
            "CreateFilePayload ownerSymbol mismatch for " + addition.relativePath() + ": addition=" + addition.ownerSymbol() + " b1=" + b1Entry.ownerSymbol()
         );
      }

      if (stagedBytes.length != addition.candidateByteCount()) {
         throw new IllegalArgumentException(
            "CreateFilePayload staged byte count mismatch for "
               + addition.relativePath()
               + ": addition="
               + addition.candidateByteCount()
               + " staged="
               + stagedBytes.length
         );
      }

      if (stagedBytes.length != b1Entry.byteCount()) {
         throw new IllegalArgumentException(
            "CreateFilePayload b1 byte count mismatch for " + addition.relativePath() + ": b1=" + b1Entry.byteCount() + " staged=" + stagedBytes.length
         );
      }

      String stagedSha = Sha256.hexDigest(stagedBytes);
      if (!stagedSha.equals(addition.candidateSha256Hex())) {
         throw new IllegalArgumentException(
            "CreateFilePayload staged SHA-256 mismatch for " + addition.relativePath() + ": addition=" + addition.candidateSha256Hex() + " staged=" + stagedSha
         );
      }

      if (!stagedSha.equals(b1Entry.sha256Hex())) {
         throw new IllegalArgumentException(
            "CreateFilePayload b1 SHA-256 mismatch for " + addition.relativePath() + ": b1=" + b1Entry.sha256Hex() + " staged=" + stagedSha
         );
      }

      this.stagedBytes = (byte[])stagedBytes.clone();
   }

   public String relativePath() {
      return this.addition.relativePath();
   }

   public FileAddition addition() {
      return this.addition;
   }

   public BaselineManifestEntry b1Entry() {
      return this.b1Entry;
   }

   public byte[] stagedBytes() {
      return (byte[])this.stagedBytes.clone();
   }

   public long byteCount() {
      return this.b1Entry.byteCount();
   }

   public String sha256Hex() {
      return this.b1Entry.sha256Hex();
   }

   @Override
   public String toString() {
      return "CreateFilePayload{rel=" + this.addition.relativePath() + ", bytes=" + this.stagedBytes.length + "}";
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else {
         return !(o instanceof CreateFilePayload other)
            ? false
            : this.addition.equals(other.addition) && this.b1Entry.equals(other.b1Entry) && Arrays.equals(this.stagedBytes, other.stagedBytes);
      }
   }

   @Override
   public int hashCode() {
      int result = this.addition.hashCode();
      result = 31 * result + this.b1Entry.hashCode();
      return 31 * result + Arrays.hashCode(this.stagedBytes);
   }
}
