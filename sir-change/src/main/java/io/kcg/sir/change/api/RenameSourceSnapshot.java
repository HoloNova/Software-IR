package io.kcg.sir.change.api;

import io.kcg.sir.change.internal.Sha256;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The exact source bytes one side of a rename was built from.
 *
 * <p>A project graph is not a stand-in for its source. Editing a comment changes these bytes while
 * leaving every node, edge and digest of the graph identical, so a revision has to be identified by
 * its source as well as by its graph. The rename planner never reads a file: the caller hands it the
 * snapshot it took, and the planner only refuses when a plan and a snapshot disagree.
 */
public record RenameSourceSnapshot(SourceId sourceId, long byteCount, String sha256Hex) {
   public RenameSourceSnapshot {
      Objects.requireNonNull(sourceId, "sourceId");
      if (byteCount < 0L) {
         throw new IllegalArgumentException("byteCount must not be negative");
      }

      if (sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }

   /**
    * The snapshot of source text held in memory: its UTF-8 bytes are the revision's bytes. Callers
    * that already have the bytes on disk pass them through this same rule.
    */
   public static RenameSourceSnapshot of(SourceId sourceId, String sourceText) {
      Objects.requireNonNull(sourceText, "sourceText");
      byte[] bytes = sourceText.getBytes(StandardCharsets.UTF_8);
      return new RenameSourceSnapshot(sourceId, bytes.length, Sha256.hex(bytes));
   }
}
