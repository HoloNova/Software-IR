package io.kcg.sir.application.api;

import java.util.List;
import java.util.Objects;

public record ChangePlanningContext(
   ChangePlanningContextFormatVersion formatVersion,
   ChangeBaselineReceipt baseline,
   String candidateSourceSha256Hex,
   String contextId,
   List<ChangePlanningTarget> targets
) {
   public ChangePlanningContext {
      Objects.requireNonNull(formatVersion, "formatVersion");
      Objects.requireNonNull(baseline, "baseline");
      Objects.requireNonNull(candidateSourceSha256Hex, "candidateSourceSha256Hex");
      Objects.requireNonNull(contextId, "contextId");
      targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
      if (!candidateSourceSha256Hex.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("candidateSourceSha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
      }

      if (!contextId.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("contextId must be a 64-character lowercase hexadecimal SHA-256 digest");
      }
   }
}
