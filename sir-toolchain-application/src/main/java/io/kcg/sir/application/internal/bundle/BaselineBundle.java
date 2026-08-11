package io.kcg.sir.application.internal.bundle;

import java.util.Arrays;
import java.util.Objects;

public final class BaselineBundle {
   private final BaselineDescriptor descriptor;
   private final byte[] sourceBytes;
   private final byte[] snapshotBytes;
   private final byte[] descriptorBytes;
   private final String baselineId;

   public BaselineBundle(BaselineDescriptor descriptor, byte[] descriptorBytes, byte[] sourceBytes, byte[] snapshotBytes, String expectedBaselineId) {
      this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
      this.descriptorBytes = (byte[])Objects.requireNonNull(descriptorBytes, "descriptorBytes").clone();
      this.sourceBytes = (byte[])Objects.requireNonNull(sourceBytes, "sourceBytes").clone();
      this.snapshotBytes = (byte[])Objects.requireNonNull(snapshotBytes, "snapshotBytes").clone();
      Objects.requireNonNull(expectedBaselineId, "expectedBaselineId");
      String recomputed = BaselineDescriptorCodec.computeBaselineId(this.descriptorBytes, this.sourceBytes, this.snapshotBytes);
      if (!recomputed.equals(expectedBaselineId)) {
         throw new IllegalArgumentException("baselineId mismatch: expected=" + expectedBaselineId + " recomputed=" + recomputed);
      }

      byte[] recanonical = BaselineDescriptorCodec.encode(descriptor);
      if (!Arrays.equals(recanonical, this.descriptorBytes)) {
         throw new IllegalArgumentException(
            "descriptorBytes are not canonical: stored length=" + this.descriptorBytes.length + " re-encoded length=" + recanonical.length
         );
      }

      if (this.sourceBytes.length != descriptor.sourceByteCount()) {
         throw new IllegalArgumentException("sourceByteCount mismatch: descriptor=" + descriptor.sourceByteCount() + " actual=" + this.sourceBytes.length);
      }

      if (this.snapshotBytes.length != descriptor.snapshotByteCount()) {
         throw new IllegalArgumentException("snapshotByteCount mismatch: descriptor=" + descriptor.snapshotByteCount() + " actual=" + this.snapshotBytes.length);
      }

      this.baselineId = recomputed;
   }

   public BaselineDescriptor descriptor() {
      return this.descriptor;
   }

   public byte[] sourceBytes() {
      return (byte[])this.sourceBytes.clone();
   }

   public byte[] snapshotBytes() {
      return (byte[])this.snapshotBytes.clone();
   }

   public byte[] descriptorBytes() {
      return (byte[])this.descriptorBytes.clone();
   }

   public String baselineId() {
      return this.baselineId;
   }

   public static BaselineBundle materialize(BaselineDescriptor descriptor, byte[] sourceBytes, byte[] snapshotBytes) {
      Objects.requireNonNull(descriptor, "descriptor");
      byte[] descriptorBytes = BaselineDescriptorCodec.encode(descriptor);
      String baselineId = BaselineDescriptorCodec.computeBaselineId(descriptorBytes, sourceBytes, snapshotBytes);
      return new BaselineBundle(descriptor, descriptorBytes, sourceBytes, snapshotBytes, baselineId);
   }
}
