package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.lowering.api.LoweredIrVersion;
import java.nio.file.Path;
import java.util.Objects;

public record ChangeBaselineReceipt(
   ChangeExecutionBaselineFormatVersion formatVersion,
   String baselineId,
   ChangeBaseRevision revision,
   Path boundOutputRoot,
   String targetId,
   LoweredIrVersion loweredIrVersion,
   String manifestDigest
) {
   public ChangeBaselineReceipt {
      Objects.requireNonNull(formatVersion, "formatVersion");
      Objects.requireNonNull(baselineId, "baselineId");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(boundOutputRoot, "boundOutputRoot");
      Objects.requireNonNull(targetId, "targetId");
      Objects.requireNonNull(loweredIrVersion, "loweredIrVersion");
      Objects.requireNonNull(manifestDigest, "manifestDigest");
      if (!boundOutputRoot.isAbsolute()) {
         throw new IllegalArgumentException("boundOutputRoot must be absolute");
      }

      if (!baselineId.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("baselineId must be a 64-character lowercase hexadecimal SHA-256 digest");
      }

      if (!manifestDigest.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("manifestDigest must be a 64-character lowercase hexadecimal SHA-256 digest");
      }

      boundOutputRoot = boundOutputRoot.normalize();
   }
}
