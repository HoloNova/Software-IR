package io.kcg.sir.change.api;

import io.kcg.sir.change.internal.RenamePlannerCore;
import java.util.List;
import java.util.Objects;

/**
 * Plans renames of declarations that carry an explicit {@code @id}, and re-checks a plan against the
 * revision pair it was computed for.
 *
 * <p>Read-only in both directions: {@link #plan} reads two semantic models and two revision
 * snapshots and returns a plan, and {@link #verify} reads a plan plus the same two snapshots.
 * Neither writes a CURRENT revision, a journal entry or a project file, and there is no method here
 * that applies a plan.
 */
public final class RenamePlanner {
   public RenameAnalysis plan(RenamePlanningInput input) {
      Objects.requireNonNull(input, "input");
      return RenamePlannerCore.plan(input);
   }

   /**
    * Re-checks that the plan still describes these revisions, including both graphs, both source
    * digests and every base file digest an apply would rely on. An empty list means the plan is still
    * valid; otherwise nothing may act on it.
    */
   public List<RenameDiagnostic> verify(
      RenamePlan plan, RenameRevisionSnapshot base, RenameRevisionSnapshot candidate
   ) {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(candidate, "candidate");
      return RenamePlannerCore.verify(plan, base, candidate);
   }
}
