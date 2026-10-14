package io.kcg.cli;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The arguments of {@code version-plan} and {@code version-apply}.
 *
 * @param expectedPlanId the plan id {@code version-plan} printed; present for {@code version-apply} only
 */
public record VersionArguments(Path repositoryRoot, Path sourceRoot, SourceId entry, Path outputRoot, Path candidateRoot, String expectedPlanId) {
   public VersionArguments {
      Objects.requireNonNull(repositoryRoot, "repositoryRoot");
      Objects.requireNonNull(sourceRoot, "sourceRoot");
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(candidateRoot, "candidateRoot");
   }
}
