package io.kcg.sir.projectgraph.api;

import java.util.Arrays;
import java.util.Objects;

public final class CanonicalProjectGraphDocument {
   private final byte[] bytes;

   public CanonicalProjectGraphDocument(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes");
      this.bytes = (byte[])bytes.clone();
   }

   public byte[] bytes() {
      return (byte[])this.bytes.clone();
   }

   public int byteLength() {
      return this.bytes.length;
   }

   @Override
   public boolean equals(Object other) {
      return this == other || other instanceof CanonicalProjectGraphDocument doc && Arrays.equals(this.bytes, doc.bytes);
   }

   @Override
   public int hashCode() {
      return Arrays.hashCode(this.bytes);
   }
}
