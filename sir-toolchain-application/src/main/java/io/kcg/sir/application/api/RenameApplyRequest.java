package io.kcg.sir.application.api;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** An explicit request to plan and atomically apply one persistent-identity declaration rename. */
public record RenameApplyRequest(
   Path stateRoot,
   String expectedBaselineId,
   Path candidateSirFile,
   Path outputRoot,
   SymbolId declarationSymbol,
   Optional<String> expectedCandidateSirSha256Hex
) {
   private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");

   public RenameApplyRequest {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(expectedBaselineId, "expectedBaselineId");
      Objects.requireNonNull(candidateSirFile, "candidateSirFile");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(declarationSymbol, "declarationSymbol");
      expectedCandidateSirSha256Hex = Objects.requireNonNull(expectedCandidateSirSha256Hex, "expectedCandidateSirSha256Hex");
      if (expectedBaselineId.isBlank()) throw new IllegalArgumentException("expectedBaselineId must not be blank");
      if (expectedCandidateSirSha256Hex.isPresent() && !LOWER_HEX_64.matcher(expectedCandidateSirSha256Hex.get()).matches())
         throw new IllegalArgumentException("expectedCandidateSirSha256Hex when present must be 64 lower-case hex characters");
   }

   public RenameApplyRequest(Path stateRoot, String expectedBaselineId, Path candidateSirFile, Path outputRoot, SymbolId declarationSymbol) {
      this(stateRoot, expectedBaselineId, candidateSirFile, outputRoot, declarationSymbol, Optional.empty());
   }

   public static RenameApplyRequest digestBound(
      Path stateRoot, String expectedBaselineId, Path candidateSirFile, Path outputRoot, SymbolId declarationSymbol, String sha256Hex
   ) {
      return new RenameApplyRequest(stateRoot, expectedBaselineId, candidateSirFile, outputRoot, declarationSymbol, Optional.of(sha256Hex));
   }
}
