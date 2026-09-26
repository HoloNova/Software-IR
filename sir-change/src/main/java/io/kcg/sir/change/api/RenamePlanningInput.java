package io.kcg.sir.change.api;

import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import java.util.Objects;

/**
 * Everything a rename plan is computed from: the locked base and the edited candidate, each as a
 * revision snapshot (its project graph plus the source bytes it came from), plus the request that
 * names the declaration.
 *
 * <p>The models decide identity (which declarations exist on each side); the graphs decide the
 * managed artifacts and files; the source snapshots decide whether the graphs still describe the
 * revisions the request was taken from. Nothing here is read from disk, so planning cannot be
 * affected by, or affect, what is on disk.
 */
public record RenamePlanningInput(
   NormalizedSemanticModel baseModel,
   NormalizedSemanticModel candidateModel,
   RenameRevisionSnapshot base,
   RenameRevisionSnapshot candidate,
   RenamePlanRequest request
) {
   public RenamePlanningInput {
      Objects.requireNonNull(baseModel, "baseModel");
      Objects.requireNonNull(candidateModel, "candidateModel");
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(request, "request");
   }
}
