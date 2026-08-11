package io.kcg.sir.application.internal.changeplanning;

import io.kcg.sir.application.api.ChangeBaselineReceipt;
import io.kcg.sir.application.api.ChangePlanningContextFormatVersion;
import io.kcg.sir.application.api.ChangePlanningSide;
import io.kcg.sir.application.api.ChangePlanningTarget;
import io.kcg.sir.application.api.ChangePlanningTargetKind;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeTarget;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ChangePlanningHasher {
   private static final byte[] TARGET_KEY_DOMAIN = "KCG-CLI-CHANGE-TARGET-KEY-V1".getBytes(StandardCharsets.UTF_8);
   private static final byte[] CONTEXT_ID_DOMAIN = "KCG-CLI-CHANGE-PLANNING-CONTEXT-V1".getBytes(StandardCharsets.US_ASCII);

   private ChangePlanningHasher() {
   }

   public static String targetKey(ChangePlanningSide side, ChangePlanningTargetKind kind, ChangeTarget target) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.writeBytes(TARGET_KEY_DOMAIN);
      frame(out, side.name());
      frame(out, kind.name());
      frame(out, target.declarationSymbol().value());
      frame(out, target.declarationNodeId().value());
      frame(out, target.targetNodeId().value());
      return Sha256.hexDigest(out.toByteArray());
   }

   public static String contextId(
      ChangePlanningContextFormatVersion formatVersion, ChangeBaselineReceipt baseline, String candidateSourceSha256Hex, List<ChangePlanningTarget> targets
   ) {
      ChangeBaseRevision rev = baseline.revision();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.writeBytes(CONTEXT_ID_DOMAIN);
      frame(out, formatVersion.name());
      frame(out, baseline.baselineId());
      frame(out, rev.sourceId().value());
      frame(out, rev.baseSourceSha256Hex());
      frame(out, rev.graphVersion().name());
      frame(out, rev.graphCanonicalDigest());
      frame(out, rev.snapshotFormatVersion().name());
      frame(out, baseline.boundOutputRoot().toString());
      frame(out, candidateSourceSha256Hex);
      frame(out, Integer.toString(targets.size()));

      for (ChangePlanningTarget t : targets) {
         frame(out, t.targetKey());
         frame(out, t.side().name());
         frame(out, t.kind().name());
         frame(out, t.target().declarationSymbol().value());
         frame(out, t.target().declarationNodeId().value());
         frame(out, t.target().targetNodeId().value());
      }

      return Sha256.hexDigest(out.toByteArray());
   }

   private static void frame(ByteArrayOutputStream out, String value) {
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      frame(out, utf8);
   }

   private static void frame(ByteArrayOutputStream out, byte[] bytes) {
      int len = bytes.length;
      out.write(len >>> 24 & 0xFF);
      out.write(len >>> 16 & 0xFF);
      out.write(len >>> 8 & 0xFF);
      out.write(len & 0xFF);
      out.writeBytes(bytes);
   }
}
