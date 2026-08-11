package io.kcg.sir.change.api;

import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.source.SourceId;
import java.util.Objects;

public record ChangeBaseRevision(
   SourceId sourceId,
   String baseSourceSha256Hex,
   GraphVersion graphVersion,
   String graphCanonicalDigest,
   ProjectGraphCanonicalFormatVersion snapshotFormatVersion
) {
   public ChangeBaseRevision {
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(baseSourceSha256Hex, "baseSourceSha256Hex");
      Objects.requireNonNull(graphVersion, "graphVersion");
      Objects.requireNonNull(graphCanonicalDigest, "graphCanonicalDigest");
      Objects.requireNonNull(snapshotFormatVersion, "snapshotFormatVersion");
      if (!baseSourceSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("baseSourceSha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }

      if (!graphCanonicalDigest.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("graphCanonicalDigest must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
