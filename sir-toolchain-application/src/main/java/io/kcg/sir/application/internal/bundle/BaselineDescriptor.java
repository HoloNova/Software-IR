package io.kcg.sir.application.internal.bundle;

import io.kcg.sir.application.api.ChangeExecutionBaselineFormatVersion;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record BaselineDescriptor(
   ChangeExecutionBaselineFormatVersion formatVersion,
   Path boundOutputRoot,
   SourceId sourceId,
   long sourceByteCount,
   String sourceSha256Hex,
   GraphVersion graphVersion,
   String graphCanonicalDigest,
   ProjectGraphCanonicalFormatVersion snapshotFormatVersion,
   long snapshotByteCount,
   String snapshotSha256Hex,
   String targetId,
   LoweredIrVersion loweredIrVersion,
   List<BaselineManifestEntry> manifest,
   String manifestDigest
) {
   public BaselineDescriptor(
      ChangeExecutionBaselineFormatVersion formatVersion,
      Path boundOutputRoot,
      SourceId sourceId,
      long sourceByteCount,
      String sourceSha256Hex,
      GraphVersion graphVersion,
      String graphCanonicalDigest,
      ProjectGraphCanonicalFormatVersion snapshotFormatVersion,
      long snapshotByteCount,
      String snapshotSha256Hex,
      String targetId,
      LoweredIrVersion loweredIrVersion,
      List<BaselineManifestEntry> manifest,
      String manifestDigest
   ) {
      Objects.requireNonNull(formatVersion, "formatVersion");
      Objects.requireNonNull(boundOutputRoot, "boundOutputRoot");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(sourceSha256Hex, "sourceSha256Hex");
      Objects.requireNonNull(graphVersion, "graphVersion");
      Objects.requireNonNull(graphCanonicalDigest, "graphCanonicalDigest");
      Objects.requireNonNull(snapshotFormatVersion, "snapshotFormatVersion");
      Objects.requireNonNull(snapshotSha256Hex, "snapshotSha256Hex");
      Objects.requireNonNull(targetId, "targetId");
      Objects.requireNonNull(loweredIrVersion, "loweredIrVersion");
      Objects.requireNonNull(manifestDigest, "manifestDigest");
      if (!boundOutputRoot.isAbsolute()) {
         throw new IllegalArgumentException("boundOutputRoot must be absolute");
      }

      if (!boundOutputRoot.equals(boundOutputRoot.normalize())) {
         throw new IllegalArgumentException("boundOutputRoot must be in normalized form: " + boundOutputRoot);
      }

      if (sourceByteCount < 0L) {
         throw new IllegalArgumentException("sourceByteCount must not be negative");
      }

      if (!sourceSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("sourceSha256Hex must be 64-char lowercase hex");
      }

      if (!graphCanonicalDigest.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("graphCanonicalDigest must be 64-char lowercase hex");
      }

      if (snapshotByteCount < 0L) {
         throw new IllegalArgumentException("snapshotByteCount must not be negative");
      }

      if (!snapshotSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("snapshotSha256Hex must be 64-char lowercase hex");
      }

      if (!manifestDigest.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("manifestDigest must be 64-char lowercase hex");
      }

      if (targetId.isBlank()) {
         throw new IllegalArgumentException("targetId must not be blank");
      }

      Objects.requireNonNull(manifest, "manifest");
      List<BaselineManifestEntry> sorted = new ArrayList<>(manifest);
      sorted.sort(Comparator.comparing(BaselineManifestEntry::relativePath));

      for (int i = 1; i < sorted.size(); i++) {
         if (sorted.get(i - 1).relativePath().equals(sorted.get(i).relativePath())) {
            throw new IllegalArgumentException("duplicate manifest relativePath: " + sorted.get(i).relativePath());
         }
      }

      manifest = List.copyOf(sorted);
      this.formatVersion = formatVersion;
      this.boundOutputRoot = boundOutputRoot;
      this.sourceId = sourceId;
      this.sourceByteCount = sourceByteCount;
      this.sourceSha256Hex = sourceSha256Hex;
      this.graphVersion = graphVersion;
      this.graphCanonicalDigest = graphCanonicalDigest;
      this.snapshotFormatVersion = snapshotFormatVersion;
      this.snapshotByteCount = snapshotByteCount;
      this.snapshotSha256Hex = snapshotSha256Hex;
      this.targetId = targetId;
      this.loweredIrVersion = loweredIrVersion;
      this.manifest = manifest;
      this.manifestDigest = manifestDigest;
   }

   public ChangeBaseRevision toBaseRevision() {
      return new ChangeBaseRevision(this.sourceId, this.sourceSha256Hex, this.graphVersion, this.graphCanonicalDigest, this.snapshotFormatVersion);
   }
}
