package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ChangeSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record ChangeApplyRequest(
   Path stateRoot, String expectedBaselineId, Path candidateSirFile, Path outputRoot, ChangeSet changeSet, Optional<String> expectedCandidateSirSha256Hex
) {
   private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");

   public ChangeApplyRequest {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(expectedBaselineId, "expectedBaselineId");
      Objects.requireNonNull(candidateSirFile, "candidateSirFile");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(changeSet, "changeSet");
      expectedCandidateSirSha256Hex = Objects.requireNonNull(expectedCandidateSirSha256Hex, "expectedCandidateSirSha256Hex");
      if (expectedBaselineId.isBlank()) {
         throw new IllegalArgumentException("expectedBaselineId must not be blank");
      }

      if (expectedCandidateSirSha256Hex.isPresent() && !LOWER_HEX_64.matcher(expectedCandidateSirSha256Hex.get()).matches()) {
         throw new IllegalArgumentException("expectedCandidateSirSha256Hex when present must be 64 lower-case hex characters");
      }
   }

   public ChangeApplyRequest(Path stateRoot, String expectedBaselineId, Path candidateSirFile, Path outputRoot, ChangeSet changeSet) {
      this(stateRoot, expectedBaselineId, candidateSirFile, outputRoot, changeSet, Optional.empty());
   }

   public static ChangeApplyRequest digestBound(
      Path stateRoot, String expectedBaselineId, Path candidateSirFile, Path outputRoot, ChangeSet changeSet, String expectedCandidateSirSha256Hex
   ) {
      Objects.requireNonNull(expectedCandidateSirSha256Hex, "expectedCandidateSirSha256Hex");
      return new ChangeApplyRequest(stateRoot, expectedBaselineId, candidateSirFile, outputRoot, changeSet, Optional.of(expectedCandidateSirSha256Hex));
   }
}
